package com.camlight.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlin.math.hypot

/** Human-readable dump of what each camera supports (also written to logcat, tag CamLight). */
object CameraInfoDump {
    fun dump(context: Context): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val text = buildString {
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            for (id in manager.cameraIdList) {
                val ch = manager.getCameraCharacteristics(id)
                appendLine()
                appendLine("── Camera $id ──")
                appendLine("facing: " + when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "external"
                })
                appendLine("hardware level: " + level(ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
                appendLine("sensor orientation: ${ch.get(CameraCharacteristics.SENSOR_ORIENTATION)}°")
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (focal != null && size != null) {
                    val eq = focal.map { "%.0f mm".format(it * 43.27f / hypot(size.width, size.height)) }
                    appendLine("focal length: ${focal.joinToString { "%.2f mm".format(it) }} (35mm equiv ≈ ${eq.joinToString()})")
                }
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map(::capName).orEmpty()
                appendLine("capabilities: ${caps.joinToString()}")
                if (Build.VERSION.SDK_INT >= 28) {
                    val phys = ch.physicalCameraIds
                    if (phys.isNotEmpty()) appendLine("physical cameras: ${phys.joinToString()}")
                }
                val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                appendLine("AE fps ranges: ${ranges?.joinToString()}")
                if (Build.VERSION.SDK_INT >= 30) {
                    appendLine("zoom ratio: ${ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)}")
                }
                appendLine("exposure time ns: ${ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)}")
                appendLine("ISO: ${ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)}")
                appendLine("AE compensation: ${ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)} step ${ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)}")
                appendLine("AE/AWB lock: ${ch.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)} / ${ch.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)}")
                appendLine("timestamp source: " + if (ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "realtime" else "unknown")
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.sortedByDescending { it.width * it.height }?.take(6)
                if (map != null && sizes != null) {
                    appendLine("YUV sizes (max fps):")
                    sizes.forEach { s ->
                        val d = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, s)
                        appendLine("  ${s.width}x${s.height}  ${if (d > 0) (1e9 / d).toInt() else "?"} fps")
                    }
                }
            }
        }
        text.lineSequence().forEach { Log.i("CamLight", it) }
        return text
    }

    private fun level(l: Int?) = when (l) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }

    private fun capName(c: Int) = when (c) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        else -> "cap$c"
    }
}
