package com.camlight.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.util.Range
import android.util.Size

/**
 * Camera2 session that feeds a preview surface plus a low-latency YUV ImageReader. Frames are
 * delivered on the camera thread; only the newest frame is ever processed.
 */
@SuppressLint("MissingPermission")
class CameraEngine(
    context: Context,
    private val onFrame: (Image) -> Unit,
    private val onError: (String) -> Unit,
) {
    data class Selected(
        val id: String,
        val width: Int,
        val height: Int,
        val fpsRange: Range<Int>,
        val minZoom: Float,
        val maxZoom: Float,
        val evMin: Int,
        val evMax: Int,
        val evStep: Float,
        val realtimeTimestamps: Boolean,
        /** Frame rate actually selected (may differ from the preferred one if unavailable). */
        val fps: Int,
        val has60: Boolean,
        val summary: String,
    )

    data class Params(val zoom: Float = 1f, val lock: Boolean = false, val ev: Int = 0)

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var params = Params()

    var selected: Selected? = null
        private set

    /** Preferred camera frame rate (30 or 60). Takes effect on the next [select]/[start]. */
    @Volatile var preferredFps = 30

    /** Forget the chosen stream so the next start re-selects (after changing [preferredFps]). */
    fun resetSelection() { selected = null }

    /** Latest per-frame camera metadata (exposure, ISO, white balance), for the diagnostics log. */
    @Volatile var meta: String = ""
        private set

    /** Picks the main back camera and the best stream: 60 fps if possible, else 1080p at 30 fps. */
    fun select(): Selected? {
        val id = manager.cameraIdList.filter { id ->
            val ch = manager.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
        }.let { ids -> if ("0" in ids) "0" else ids.firstOrNull() } ?: return null

        val ch = manager.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toSet() ?: emptySet()
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()

        data class Option(val size: Size, val fps: Int, val range: Range<Int>)

        val options = SIZES.filter { it in yuv }.flatMap { size ->
            val dur = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
            val maxFps = if (dur > 0) (1e9 / dur).toInt() else 30
            listOf(60, 30).mapNotNull { fps ->
                val r = ranges.firstOrNull { it.lower == fps && it.upper == fps }
                if (r != null && maxFps >= fps - 1) Option(size, fps, r) else null
            }
        }
        val best = options.firstOrNull { it.fps == preferredFps } ?: options.firstOrNull() ?: return null

        val zoomRange = if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val maxZoom = zoomRange?.upper
            ?: ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val ev = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val realtime = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        return Selected(
            id = id,
            width = best.size.width,
            height = best.size.height,
            fpsRange = best.range,
            minZoom = zoomRange?.lower ?: 1f,
            maxZoom = minOf(maxZoom, 10f),
            evMin = ev?.lower ?: 0,
            evMax = ev?.upper ?: 0,
            evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f,
            realtimeTimestamps = realtime,
            fps = best.fps,
            has60 = options.any { it.fps == 60 },
            summary = "camera $id · ${best.size.width}x${best.size.height} @ ${best.fps} fps",
        ).also { selected = it }
    }

    fun start(initial: Params) {
        val sel = selected ?: select() ?: run { onError("No usable back camera"); return }
        stop()
        params = initial
        val t = HandlerThread("camlight-camera", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        thread = t
        val h = Handler(t.looper).also { handler = it }
        try {
            characteristics = manager.getCameraCharacteristics(sel.id)
            reader = ImageReader.newInstance(sel.width, sel.height, ImageFormat.YUV_420_888, 3).apply {
                setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        onFrame(img)
                    } finally {
                        img.close()
                    }
                }, h)
            }
            manager.openCamera(sel.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    onError("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    onError("Camera error $error")
                }
            }, h)
        } catch (e: Exception) {
            onError("Could not open camera: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice) {
        val targets = listOfNotNull(reader?.surface)
        try {
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    apply()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("Camera session configuration failed")
                }
            }, handler)
        } catch (e: Exception) {
            onError("Could not start camera session: ${e.message}")
        }
    }

    fun update(p: Params) {
        params = p
        handler?.post { apply() }
    }

    private fun apply() {
        val d = device ?: return
        val s = session ?: return
        val ch = characteristics ?: return
        val sel = selected ?: return
        val p = params
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            reader?.surface?.let { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, sel.fpsRange)
            b.set(CaptureRequest.CONTROL_AE_LOCK, p.lock)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, p.lock)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, p.ev)
            pick(ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                ?.let { b.set(CaptureRequest.CONTROL_AF_MODE, it) }
            // Less in-camera processing = less latency and less color re-interpretation.
            pick(ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                CaptureRequest.NOISE_REDUCTION_MODE_FAST)?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
            pick(ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                CaptureRequest.EDGE_MODE_OFF)?.let { b.set(CaptureRequest.EDGE_MODE, it) }
            if (Build.VERSION.SDK_INT >= 30) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, p.zoom)
            } else {
                ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, crop(it, p.zoom)) }
            }
            s.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult,
                ) {
                    val gains = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)
                    val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                    val dur = result.get(android.hardware.camera2.CaptureResult.SENSOR_FRAME_DURATION)
                    meta = "exp=%.1fms iso=%s frame=%.1fms ae=%s awb=%s wbGains=%s zoom=%s".format(
                        (exp ?: 0L) / 1e6, iso, (dur ?: 0L) / 1e6,
                        result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE),
                        result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE),
                        gains?.let { "R%.2f/G%.2f/B%.2f".format(it.red, (it.greenEven + it.greenOdd) / 2f, it.blue) } ?: "?",
                        if (Build.VERSION.SDK_INT >= 30) result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO) else "n/a",
                    )
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "apply failed: ${e.message}")
        }
    }

    private fun crop(a: Rect, zoom: Float): Rect {
        val w = (a.width() / zoom).toInt()
        val h = (a.height() / zoom).toInt()
        val x = a.left + (a.width() - w) / 2
        val y = a.top + (a.height() - h) / 2
        return Rect(x, y, x + w, y + h)
    }

    private fun pick(available: IntArray?, wanted: Int): Int? = if (available?.contains(wanted) == true) wanted else null

    fun stop() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private companion object {
        const val TAG = "CamLight"
        val SIZES = listOf(Size(1920, 1080), Size(1280, 720))
    }
}
