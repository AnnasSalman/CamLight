package com.camlight.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.camlight.app.camera.AmbientProcessor
import com.camlight.app.camera.CameraEngine
import com.camlight.app.camera.CameraInfoDump
import com.camlight.app.camera.Look
import com.camlight.app.camera.Smoothing
import com.camlight.app.camera.Pt
import com.camlight.app.camera.Quad
import com.camlight.app.camera.TvDetector
import com.camlight.app.camera.ZoneDot
import com.camlight.app.camera.ZoneMapper
import com.camlight.app.camera.Zebra
import com.camlight.app.data.CameraSettings
import com.camlight.app.data.ConfigStore
import com.camlight.app.model.StripConfig
import com.camlight.app.remote.Pattern
import com.camlight.app.remote.RemoteApi
import com.camlight.app.remote.RemoteServer
import com.camlight.app.wled.DdpSender
import com.camlight.app.wled.WledClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** What the TV test page last reported about itself (size, full screen, browser). */
class TvPage(val width: Int, val height: Int, val fullscreen: Boolean, val agent: String, val seenAtMs: Long)

/** The live image and zebra, kept out of [CameraUiState] so only the preview redraws at 10 Hz. */
class PreviewFrame(val bitmap: Bitmap?, val version: Int, val zebra: Zebra?)

data class CameraStats(val fps: Int = 0, val processMs: Float = 0f, val ageMs: Float = 0f)

data class CameraUiState(
    val selected: CameraEngine.Selected? = null,
    val params: CameraEngine.Params = CameraEngine.Params(),
    val brightness: Float = 0.8f,
    val quad: Quad = Quad.DEFAULT,
    val lightsOn: Boolean = false,
    val showZebra: Boolean = false,
    val look: String = "basic",
    val smoothing: Smoothing = Smoothing.NORMAL,
    val white: FloatArray? = null,
    val whiteStatus: String? = null,
    /** Preferred camera frame rate (30 or 60); the rate in use is `selected.fps`. */
    val cameraFps: Int = 30,
    val stats: CameraStats = CameraStats(),
    val dots: List<ZoneDot> = emptyList(),
    val flip180: Boolean = false,
    val detecting: Boolean = false,
    val detectStatus: String? = null,
    val silhouette: Quad? = null,
    val debug: Bitmap? = null,
    val showDebug: Boolean = false,
    val saturation: Float = 1.2f,
    val warmth: Float = 0.7f,
    /** WLED color gamma as reported by the controller (1.0 = off); null until known. */
    val wledGamma: Float? = null,
    /** Whether WLED applies that gamma to realtime frames (null = unknown). */
    val gammaOnRealtime: Boolean? = null,
    val gammaOriginal: Float = 0f,
    val gammaBusy: Boolean = false,
    val black: FloatArray? = null,
    val blackStatus: String? = null,
    val grayTest: Boolean = false,
    /** Address of the remote control page while the camera screen is open. */
    val remoteUrl: String? = null,
    /** Address (short code) to open in the TV's browser, and the pattern it is asked to show. */
    val patternsUrl: String? = null,
    val pattern: String = Pattern.NONE,
    val error: String? = null,
)

class CameraViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = ConfigStore(app)
    private val processor = AmbientProcessor()
    private val client = WledClient()
    private val engine = CameraEngine(app, ::onFrame, ::onEngineError)

    private val initial = store.loadCamera()
    private val _state = MutableStateFlow(
        CameraUiState(
            params = CameraEngine.Params(zoom = initial.zoom),
            brightness = initial.brightness,
            quad = initial.quad,
            saturation = initial.saturation,
            warmth = initial.warmth,
            black = initial.black,
            look = Look.byId(initial.look).id,
            white = initial.white,
            cameraFps = initial.fps,
            smoothing = Smoothing.entries.firstOrNull { it.name == initial.smoothing } ?: Smoothing.NORMAL,
        ),
    )
    val state: StateFlow<CameraUiState> = _state

    private val _preview = MutableStateFlow(PreviewFrame(null, 0, null))
    val preview: StateFlow<PreviewFrame> = _preview

    private val wifiLock = (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(
        if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "camlight",
    ).apply { setReferenceCounted(false) }
    @Volatile private var sendMs = 0f
    @Volatile private var sendMaxMs = 0f

    private var host: String? = null
    private var strip = StripConfig()
    @Volatile private var sender: DdpSender? = null
    @Volatile private var latestFrame: ByteArray? = null
    private var sendJob: Job? = null
    private var tickerJob: Job? = null
    private var detectJob: Job? = null
    private var lastFrames = 0L
    private var lastStatsNs = 0L
    private var senderErrors = 0
    private var lastPreviewVersion = 0
    @Volatile private var detectFrame = ByteArray(0)

    private var testJob: Job? = null
    @Volatile private var cameraRunning = false

    init {
        processor.brightness = initial.brightness
        processor.saturation = initial.saturation
        processor.warmth = initial.warmth
        processor.blackManual = initial.black
        processor.look = Look.byId(initial.look)
        processor.whiteGains = initial.white
        engine.preferredFps = initial.fps
        processor.smoothBase = (Smoothing.entries.firstOrNull { it.name == initial.smoothing } ?: Smoothing.NORMAL).base
    }

    /** Called when the camera screen opens. */
    fun enter(host: String, strip: StripConfig) {
        this.host = host
        this.strip = strip
        rebuildZones()
        viewModelScope.launch { refreshGamma(host) }
        startRemote()
        tickerJob?.cancel()
        lastFrames = 0
        lastStatsNs = System.nanoTime()
        runCatching { if (!wifiLock.isHeld) wifiLock.acquire() }
        var tick = 0
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                val ver = processor.grayVersion
                if (ver != lastPreviewVersion) {
                    lastPreviewVersion = ver
                    _preview.value = PreviewFrame(processor.previewBitmap, ver, processor.zebra)
                }
                tick++
                if (tick % 5 == 0) {
                    val now = System.nanoTime()
                    val frames = processor.frames
                    val fps = ((frames - lastFrames) * 1e9 / (now - lastStatsNs)).toInt()
                    lastFrames = frames
                    lastStatsNs = now
                    _state.update { it.copy(stats = CameraStats(fps, processor.avgProcessMs, processor.lastAgeMs)) }
                    if (tick % 20 == 0) logDiagnostics(fps)
                }
            }
        }
    }

    /** Called when the camera screen closes. */
    fun leave() {
        tickerJob?.cancel()
        detectJob?.cancel()
        testJob?.cancel()
        stopRemote()
        stopSender(exitLive = true)
        stopCamera()
        runCatching { if (wifiLock.isHeld) wifiLock.release() }
        _state.update { it.copy(lightsOn = false, detecting = false, grayTest = false) }
        store.saveCamera(settings())
    }

    fun startCamera() {
        val sel = engine.selected ?: engine.select()
        if (sel == null) {
            _state.update { it.copy(error = "No usable back camera found") }
            return
        }
        val p = _state.value.params.copy(zoom = _state.value.params.zoom.coerceIn(sel.minZoom, sel.maxZoom))
        _state.update { it.copy(selected = sel, params = p, error = null) }
        rebuildZones()
        cameraRunning = true
        engine.start(p)
    }

    fun stopCamera() {
        cameraRunning = false
        engine.stop()
    }

    /** Switches the camera between 30 and 60 fps; restarts the camera session if it is running. */
    fun setCameraFps(fps: Int) {
        val target = if (fps == 60) 60 else 30
        if (_state.value.cameraFps == target || _state.value.detecting) return
        engine.preferredFps = target
        _state.update { it.copy(cameraFps = target) }
        Log.i(TAG, "camera fps -> $target")
        if (cameraRunning) {
            engine.stop()
            engine.resetSelection()
            startCamera()
        }
    }

    fun setFlip(flip: Boolean) {
        processor.flip = flip
        if (_state.value.flip180 == flip) return
        _state.update { it.copy(flip180 = flip) }
        rebuildZones()
    }

    fun setZoom(zoom: Float) = updateParams { it.copy(zoom = zoom) }

    fun setEv(ev: Int) = updateParams { it.copy(ev = ev) }

    fun setLock(lock: Boolean) = updateParams { it.copy(lock = lock) }

    private fun updateParams(f: (CameraEngine.Params) -> CameraEngine.Params) {
        val p = f(_state.value.params)
        _state.update { it.copy(params = p) }
        engine.update(p)
    }

    fun setBrightness(b: Float) {
        processor.brightness = b
        _state.update { it.copy(brightness = b) }
    }

    /** Switch to one of the A-F looks (also resets saturation and warmth to that look's values). */
    fun setLook(id: String) {
        val lk = Look.byId(id)
        processor.look = lk
        processor.saturation = lk.saturation
        processor.warmth = lk.warmth
        lk.speed?.let { processor.smoothBase = it.base }
        _state.update { it.copy(look = lk.id, saturation = lk.saturation, warmth = lk.warmth, smoothing = lk.speed ?: it.smoothing) }
        Log.i(TAG, "look -> ${lk.id} ${lk.name}")
    }

    fun setSmoothing(s: Smoothing) {
        processor.smoothBase = s.base
        _state.update { it.copy(smoothing = s) }
        Log.i(TAG, "smoothing -> ${s.label}")
    }

    fun setSaturation(s: Float) {
        processor.saturation = s
        _state.update { it.copy(saturation = s) }
    }

    fun setWarmth(w: Float) {
        processor.warmth = w
        _state.update { it.copy(warmth = w) }
    }

    /** Reads how gamma applies to realtime frames and encodes accordingly (linear when WLED skips gamma). */
    private suspend fun refreshGamma(host: String) {
        val info = client.gammaInfo(host)
        processor.ledGamma = info.effective
        _state.update {
            it.copy(wledGamma = info.col, gammaOnRealtime = info.appliedToRealtime, gammaOriginal = store.originalGamma)
        }
        Log.i(TAG, "gamma: col=${info.col} appliedToRealtime=${info.appliedToRealtime} -> encoding gamma ${info.effective}")
    }

    /**
     * Sets WLED's color gamma. Only useful when WLED applies gamma to realtime frames; the original value is
     * remembered so it can be restored.
     */
    fun setWledGammaOff(off: Boolean) {
        val h = host ?: return
        if (_state.value.gammaBusy) return
        _state.update { it.copy(gammaBusy = true) }
        viewModelScope.launch {
            val current = client.colorGamma(h)
            val target = if (off) {
                if (current != null && current > 1.05f && store.originalGamma == 0f) store.originalGamma = current
                1.0f
            } else {
                store.originalGamma.takeIf { it > 1.05f } ?: 2.8f
            }
            val now = client.setColorGamma(h, target)
            refreshGamma(h)
            _state.update {
                it.copy(
                    gammaBusy = false,
                    error = if (now == null || kotlin.math.abs(now - target) > 0.05f) "WLED did not accept the gamma change." else it.error,
                )
            }
        }
    }

    /** Measures the camera's black level while the TV shows black (or is off). */
    fun calibrateBlack() {
        if (_state.value.detecting) return
        setGrayTest(false)
        setLights(false)
        viewModelScope.launch { measureBlack() }
    }

    private suspend fun measureBlack() {
        run {
            _state.update { it.copy(blackStatus = "Measuring black… keep the TV off or fully black.") }
            processor.beginCapture(60, 1.1)
            val deadline = System.currentTimeMillis() + 8000
            while (processor.captureResult == null && System.currentTimeMillis() < deadline) delay(50)
            val r = processor.captureResult
            when {
                r == null -> _state.update { it.copy(blackStatus = "No camera frames, is the camera running?") }
                r.max() > AmbientProcessor.BLACK_MANUAL_CAP -> _state.update {
                    it.copy(blackStatus = "That does not look black (reads %.0f%%). TV off or a black screen, please.".format(r.max() * 100))
                }
                else -> {
                    processor.blackManual = r
                    _state.update {
                        it.copy(black = r, blackStatus = "Black saved: R %.1f%%  G %.1f%%  B %.1f%%".format(r[0] * 100, r[1] * 100, r[2] * 100))
                    }
                }
            }
        }
    }

    /**
     * Measures how the camera sees a plain white TV screen and stores gains that make it neutral. This folds
     * the camera's color cast, including the cast from room lighting, into the calibration.
     */
    fun calibrateWhite() {
        if (_state.value.detecting) return
        setGrayTest(false)
        setLights(false)
        viewModelScope.launch { measureWhite() }
    }

    private suspend fun measureWhite() {
        run {
            _state.update { it.copy(whiteStatus = "Measuring white… keep the TV on a plain white screen.") }
            processor.beginCapture(60, 1.0)
            val deadline = System.currentTimeMillis() + 8000
            while (processor.captureResult == null && System.currentTimeMillis() < deadline) delay(50)
            val r = processor.captureResult
            if (r == null) {
                _state.update { it.copy(whiteStatus = "No camera frames, is the camera running?") }
                return@run
            }
            if (r.max() > 0.92f) {
                _state.update {
                    it.copy(whiteStatus = "The white screen is over-exposed (a channel reads %.0f%%). Lower the exposure until the zebra is empty, then try again.".format(r.max() * 100))
                }
                return@run
            }
            val black = processor.blackInUse
            val c = FloatArray(3) { (r[it] - black[it]).coerceAtLeast(0f) }
            if (c[1] < MIN_WHITE_LEVEL) {
                _state.update {
                    it.copy(whiteStatus = "That does not look like a bright white screen (reads %.0f%%). Show a plain white image, or raise the camera exposure.".format(r[1] * 100))
                }
                return@run
            }
            val gains = floatArrayOf(
                (c[1] / c[0].coerceAtLeast(1e-4f)).coerceIn(0.4f, 2.5f),
                1f,
                (c[1] / c[2].coerceAtLeast(1e-4f)).coerceIn(0.4f, 2.5f),
            )
            processor.whiteGains = gains
            _state.update {
                it.copy(white = gains, whiteStatus = "White saved: R×%.2f  G×1.00  B×%.2f".format(gains[0], gains[2]))
            }
            Log.i(TAG, "white calibration: raw=${r.joinToString { "%.3f".format(it) }} gains=${gains.joinToString { "%.2f".format(it) }}")
        }
    }

    fun clearWhite() {
        processor.whiteGains = null
        _state.update { it.copy(white = null, whiteStatus = "No white calibration.") }
    }

    fun clearBlack() {
        processor.blackManual = null
        _state.update { it.copy(black = null, blackStatus = "Learning black automatically.") }
    }

    /** Shows gray steps through the real output stage so the user can judge neutrality on the strip. */
    fun setGrayTest(on: Boolean) {
        if (on == _state.value.grayTest) return
        val h = host ?: return
        testJob?.cancel()
        if (!on) {
            _state.update { it.copy(grayTest = false) }
            viewModelScope.launch { client.exitLive(h) }
            return
        }
        setLights(false)
        _state.update { it.copy(grayTest = true) }
        testJob = viewModelScope.launch(Dispatchers.IO) {
            val total = strip.totalLeds
            val used = strip.segments.flatMap { it.indices(total) }
            try {
                DdpSender(h).use { s ->
                    while (isActive) { s.send(processor.grayTestFrame(used, total)); delay(33) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(grayTest = false, error = "Lost connection to WLED: ${e.message}") }
            }
        }
    }

    fun setShowZebra(on: Boolean) = _state.update { it.copy(showZebra = on) }

    fun setShowDebug(on: Boolean) = _state.update { it.copy(showDebug = on) }

    fun moveCorner(index: Int, p: Pt) {
        _state.update { it.copy(quad = it.quad.with(index, Pt(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f)))) }
        rebuildZones()
    }

    fun resetCorners() {
        _state.update { it.copy(quad = Quad.DEFAULT, silhouette = null) }
        rebuildZones()
    }

    // ───────────── ambient output ─────────────

    fun setLights(on: Boolean) {
        if (on == _state.value.lightsOn) return
        if (on && _state.value.detecting) return
        if (on) setGrayTest(false)
        _state.update { it.copy(lightsOn = on) }
        val h = host ?: return
        if (on) {
            senderErrors = 0
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { DdpSender(h) }
                    .onSuccess { sender = it; startSendLoop(it) }
                    .onFailure { e -> _state.update { s -> s.copy(lightsOn = false, error = "Cannot send to $h: ${e.message}") } }
            }
        } else {
            stopSender(exitLive = true)
        }
    }

    private fun stopSender(exitLive: Boolean) {
        sendJob?.cancel()
        sendJob = null
        val old = sender
        sender = null
        old?.close()
        val h = host
        if (exitLive && old != null && h != null) viewModelScope.launch { client.exitLive(h) }
    }

    // ───────────── automatic TV detection (LED halo) ─────────────

    /**
     * Lights the strip, lets exposure settle, locks it, then compares frames with the strip lit and off.
     * The TV body blocks the wall glow, so the TV is the dark hole inside the halo.
     */
    fun detectTv() {
        val h = host ?: return
        if (_state.value.detecting) return
        setGrayTest(false)
        setLights(false)
        detectJob?.cancel()
        detectJob = viewModelScope.launch(Dispatchers.Default) {
            val wasLocked = _state.value.params.lock
            val total = strip.totalLeds
            detectFrame = ByteArray(total * 3)
            val used = strip.segments.flatMap { it.indices(total) }
            if (used.isEmpty()) {
                status("No LEDs are assigned. Set up the strip first.", running = false)
                return@launch
            }
            val level = levelByte(DETECT_LINEAR)
            val lit = ByteArray(total * 3).also { f -> used.forEach { i -> for (c in 0..2) f[i * 3 + c] = level.toByte() } }
            val dark = ByteArray(total * 3)
            val sock = try {
                withContext(Dispatchers.IO) { DdpSender(h) }
            } catch (e: Exception) {
                status("Cannot reach WLED: ${e.message}", running = false)
                return@launch
            }
            val keepAlive = launch(Dispatchers.IO) {
                try {
                    while (isActive) { sock.send(detectFrame); delay(30) }
                } catch (_: Exception) { }
            }
            try {
                _state.update { it.copy(detecting = true, silhouette = null, debug = null) }
                if (wasLocked) updateParams { it.copy(lock = false) }
                status("Lighting the strip…", running = true)
                detectFrame = lit
                delay(1600) // let auto-exposure settle on the lit scene
                updateParams { it.copy(lock = true) }
                delay(400)

                val cycles = 3
                val diffs = ArrayList<FloatArray>()
                for (c in 1..cycles) {
                    status("Measuring ($c/$cycles)…", running = true)
                    // Wait for the LEDs and the camera pipeline to catch up, then skip two frames that may predate the change.
                    detectFrame = lit
                    delay(900)
                    val a = captureGray(6, skip = 2) ?: throw IllegalStateException("No camera frames. Is the camera running?")
                    detectFrame = dark
                    delay(900)
                    val b = captureGray(6, skip = 2) ?: throw IllegalStateException("No camera frames. Is the camera running?")
                    val d = FloatArray(a.size) { (a[it] - b[it]).coerceAtLeast(0f) }
                    Log.i(TAG, "detect cycle $c: peak diff=%.1f litMean=%.1f darkMean=%.1f".format(d.max(), a.average(), b.average()))
                    diffs += d
                }
                // Per pixel minimum over the cycles: TV picture changes between cycles cancel, the wall glow stays.
                val diff = FloatArray(diffs[0].size) { i -> diffs.minOf { it[i] } }

                detectFrame = dark
                status("Analysing…", running = true)
                val outcome = TvDetector.detect(diff, AmbientProcessor.PREVIEW_W, AmbientProcessor.PREVIEW_H)
                Log.i(TAG, "detect result: ${outcome.message} | ${engine.meta} | gamma=${processor.ledGamma} level=$level")
                val debugBmp = diffBitmap(diff)
                val r = outcome.result
                if (r != null) {
                    _state.update { it.copy(quad = r.picture, silhouette = r.silhouette, debug = debugBmp) }
                    rebuildZones()
                    status(r.message + " Drag the corners to fine-tune.", running = false)
                } else {
                    _state.update { it.copy(debug = debugBmp) }
                    status(outcome.message, running = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status(e.message ?: "Detection failed", running = false)
            } finally {
                keepAlive.cancel()
                sock.close()
                client.exitLive(h)
                updateParams { it.copy(lock = wasLocked) }
                _state.update { it.copy(detecting = false) }
            }
        }
    }

    /** Lights the first and last LEDs of each side so the user can check corners on the real TV. */
    fun showCornersOnTv() {
        val h = host ?: return
        if (_state.value.detecting) return
        setLights(false)
        detectJob?.cancel()
        detectJob = viewModelScope.launch(Dispatchers.IO) {
            val total = strip.totalLeds
            val f = ByteArray(total * 3)
            strip.segments.forEach { seg ->
                val idx = seg.indices(total)
                (idx.take(2) + idx.takeLast(2)).forEach { i -> for (c in 0..2) f[i * 3 + c] = levelByte(CORNER_LINEAR).toByte() }
            }
            _state.update { it.copy(detecting = true, detectStatus = "Corner LEDs are lit on the TV…") }
            try {
                DdpSender(h).use { s ->
                    val end = System.currentTimeMillis() + 6000
                    while (isActive && System.currentTimeMillis() < end) { s.send(f); delay(30) }
                }
            } catch (_: Exception) { }
            client.exitLive(h)
            _state.update { it.copy(detecting = false, detectStatus = null) }
        }
    }

    private fun status(text: String, running: Boolean) {
        _state.update { it.copy(detectStatus = text, detecting = running) }
    }

    /** Average of [n] fresh preview-resolution gray frames, or null if the camera is not delivering. */
    private suspend fun captureGray(n: Int, skip: Int = 0): FloatArray? {
        val size = AmbientProcessor.PREVIEW_W * AmbientProcessor.PREVIEW_H
        val acc = FloatArray(size)
        var last = processor.grayVersion
        var got = 0
        var skipped = 0
        val deadline = System.currentTimeMillis() + 6000
        while (got < n && System.currentTimeMillis() < deadline) {
            val v = processor.grayVersion
            val g = processor.gray
            if (v != last && g != null) {
                last = v
                if (skipped < skip) {
                    skipped++
                } else {
                    for (i in 0 until size) acc[i] += (g[i].toInt() and 0xFF).toFloat()
                    got++
                }
            }
            delay(20)
        }
        if (got < n) return null
        for (i in 0 until size) acc[i] /= n
        return acc
    }

    private fun diffBitmap(diff: FloatArray): Bitmap {
        val max = diff.max().coerceAtLeast(1f)
        val px = IntArray(diff.size) {
            val v = (diff[it] / max * 255f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, AmbientProcessor.PREVIEW_W, AmbientProcessor.PREVIEW_H, Bitmap.Config.ARGB_8888)
    }

    // ───────────── helpers ─────────────

    // ───────────── remote control web page ─────────────

    private var remote: RemoteServer? = null
    private var jpegVersion = -1
    private var jpegCache: ByteArray? = null
    private var debugSrc: Bitmap? = null
    private var debugJpeg: ByteArray? = null

    @Volatile private var tvPage: TvPage? = null

    private fun startRemote() {
        if (remote != null) return
        val page = runCatching { app.assets.open("remote.html").use { it.readBytes() } }.getOrNull() ?: return
        val tvHtml = runCatching { app.assets.open("patterns.html").use { it.readBytes() } }.getOrDefault(ByteArray(0))
        val code = store.patternCode
        val server = RemoteServer(remoteApi, store.remoteKey, page, tvHtml, code)
        val port = server.start() ?: run {
            _state.update { it.copy(error = "Could not start the remote page (no free port).") }
            return
        }
        remote = server
        val ip = RemoteServer.localIp()
        val url = ip?.let { "http://$it:$port/?k=${store.remoteKey}" }
        val tvUrl = ip?.let { "http://$it:$port/p/$code" }
        _state.update { it.copy(remoteUrl = url, patternsUrl = tvUrl) }
        Log.i(TAG, "remote page: $url  tv test page: $tvUrl")
    }

    private fun stopRemote() {
        remote?.stop()
        remote = null
        tvPage = null
        _state.update { it.copy(remoteUrl = null, patternsUrl = null, pattern = Pattern.NONE) }
    }

    private fun tvPageConnected() = tvPage?.let { System.currentTimeMillis() - it.seenAtMs < TV_PAGE_TIMEOUT_MS } == true

    fun setPattern(id: String) {
        val valid = if (id == Pattern.NONE || Pattern.byId(id) != null) id else Pattern.NONE
        _state.update { it.copy(pattern = valid) }
    }

    /**
     * Black then white on the TV's own test page, measured with the existing black and white procedures.
     * Needs the TV page to be open (it reports in every 300 ms).
     */
    fun calibrateWithTvPage() {
        if (_state.value.detecting) return
        if (!tvPageConnected()) {
            _state.update { it.copy(blackStatus = "The TV test page is not open. Open ${it.patternsUrl ?: "the TV test page address"} in the TV's browser first.") }
            return
        }
        setGrayTest(false)
        setLights(false)
        viewModelScope.launch {
            setPattern("black")
            _state.update { it.copy(blackStatus = "Black pattern on the TV…") }
            delay(PATTERN_SETTLE_MS)
            measureBlack()
            setPattern("white")
            _state.update { it.copy(whiteStatus = "White pattern on the TV…") }
            delay(PATTERN_SETTLE_MS)
            measureWhite()
            setPattern(Pattern.NONE)
        }
    }

    private fun jpeg(bmp: Bitmap): ByteArray {
        val o = java.io.ByteArrayOutputStream(48_000)
        bmp.compress(Bitmap.CompressFormat.JPEG, 72, o)
        return o.toByteArray()
    }

    private val remoteApi = object : RemoteApi {
        @Synchronized
        override fun frame(debug: Boolean): ByteArray? {
            if (debug) {
                val d = _state.value.debug ?: return null
                if (d !== debugSrc) { debugSrc = d; debugJpeg = jpeg(d) }
                return debugJpeg
            }
            val ver = processor.grayVersion
            val bmp = processor.previewBitmap ?: return null
            if (ver != jpegVersion || jpegCache == null) { jpegCache = jpeg(bmp); jpegVersion = ver }
            return jpegCache
        }

        override fun state(): String = remoteState()

        override fun pattern(info: Map<String, String>): String {
            tvPage = TvPage(
                width = info["w"]?.toIntOrNull() ?: 0,
                height = info["h"]?.toIntOrNull() ?: 0,
                fullscreen = info["fs"] == "1",
                agent = (info["ua"] ?: "").take(120),
                seenAtMs = System.currentTimeMillis(),
            )
            val id = _state.value.pattern
            val p = Pattern.byId(id)
            val o = org.json.JSONObject()
            if (p == null) {
                o.put("id", Pattern.NONE)
            } else {
                o.put("id", p.id).put("label", p.label).put("rgb", p.rgb).put("style", p.style)
            }
            return o.toString()
        }

        override fun command(json: String): String = remoteCommand(json)
    }

    private fun remoteState(): String {
        val s = _state.value
        val sel = s.selected
        val o = org.json.JSONObject()
        fun quadArr(q: Quad) = org.json.JSONArray().also { a -> q.toList().forEach { a.put(it.x.toDouble()); a.put(it.y.toDouble()) } }
        o.put("quad", quadArr(s.quad))
        o.put("silhouette", s.silhouette?.let(::quadArr) ?: org.json.JSONObject.NULL)
        o.put("dots", org.json.JSONArray().also { a ->
            s.dots.forEach { d -> a.put(org.json.JSONArray().put(d.at.x.toDouble()).put(d.at.y.toDouble()).put(d.rgb)) }
        })
        processor.zebra?.let { z ->
            val bits = StringBuilder(z.clipped.size)
            val cells = if (s.flip180) z.clipped.reversedArray() else z.clipped
            cells.forEach { bits.append(if (it) '1' else '0') }
            o.put("zebra", org.json.JSONObject().put("cols", z.cols).put("rows", z.rows).put("cells", bits.toString()))
        }
        o.put("lights", s.lightsOn).put("detecting", s.detecting).put("detectStatus", s.detectStatus ?: org.json.JSONObject.NULL)
        o.put("look", s.look).put("smoothing", s.smoothing.name)
        o.put("whiteStatus", s.whiteStatus ?: org.json.JSONObject.NULL).put("hasWhite", s.white != null)
        o.put("cameraFps", s.cameraFps).put("fpsActual", sel?.fps ?: 0).put("has60", sel?.has60 ?: false)
        o.put("pattern", s.pattern).put("patternsUrl", s.patternsUrl ?: org.json.JSONObject.NULL)
        o.put("patterns", org.json.JSONArray().also { a -> Pattern.ALL.forEach { a.put(org.json.JSONObject().put("id", it.id).put("label", it.label)) } })
        val tv = tvPage
        o.put("tvPage", org.json.JSONObject()
            .put("connected", tvPageConnected())
            .put("w", tv?.width ?: 0).put("h", tv?.height ?: 0)
            .put("fs", tv?.fullscreen ?: false).put("ua", tv?.agent ?: ""))
        o.put("brightness", s.brightness.toDouble()).put("saturation", s.saturation.toDouble()).put("warmth", s.warmth.toDouble())
        o.put("zoom", s.params.zoom.toDouble()).put("zoomMin", (sel?.minZoom ?: 1f).toDouble()).put("zoomMax", (sel?.maxZoom ?: 1f).toDouble())
        o.put("ev", s.params.ev).put("evMin", sel?.evMin ?: 0).put("evMax", sel?.evMax ?: 0).put("evStep", (sel?.evStep ?: 1f).toDouble())
        o.put("lock", s.params.lock).put("gray", s.grayTest).put("showZebra", s.showZebra)
        o.put("showDebug", s.showDebug && s.debug != null).put("hasDebug", s.debug != null)
        o.put("blackStatus", s.blackStatus ?: org.json.JSONObject.NULL)
        o.put("summary", sel?.summary ?: "starting camera…")
        o.put("stats", org.json.JSONObject().put("fps", s.stats.fps).put("ms", s.stats.processMs.toDouble()).put("age", s.stats.ageMs.toDouble()))
        o.put("error", s.error ?: org.json.JSONObject.NULL)
        o.put("looks", org.json.JSONArray().also { a ->
            Look.ALL.forEach {
                a.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("desc", it.description).put("test", it.test))
            }
        })
        o.put("speeds", org.json.JSONArray().also { a ->
            Smoothing.entries.forEach { a.put(org.json.JSONObject().put("id", it.name).put("label", it.label)) }
        })
        return o.toString()
    }

    private fun remoteCommand(json: String): String {
        return try {
            val c = org.json.JSONObject(json)
            when (c.getString("a")) {
                "corner" -> moveCorner(c.getInt("i").coerceIn(0, 3), Pt(c.getDouble("x").toFloat(), c.getDouble("y").toFloat()))
                "resetCorners" -> resetCorners()
                "detect" -> detectTv()
                "showCorners" -> showCornersOnTv()
                "lights" -> setLights(c.getBoolean("on"))
                "look" -> setLook(c.getString("id"))
                "cameraFps" -> setCameraFps(c.getInt("v"))
                "pattern" -> setPattern(c.getString("id"))
                "calibrateTv" -> calibrateWithTvPage()
                "calibrateWhite" -> calibrateWhite()
                "clearWhite" -> clearWhite()
                "smoothing" -> Smoothing.entries.firstOrNull { it.name == c.getString("name") }?.let(::setSmoothing)
                "brightness" -> setBrightness(c.getDouble("v").toFloat().coerceIn(0.05f, 1f))
                "saturation" -> setSaturation(c.getDouble("v").toFloat().coerceIn(1f, 1.6f))
                "warmth" -> setWarmth(c.getDouble("v").toFloat().coerceIn(0f, 1f))
                "zoom" -> {
                    val sel = _state.value.selected
                    setZoom(c.getDouble("v").toFloat().coerceIn(sel?.minZoom ?: 1f, sel?.maxZoom ?: 1f))
                }
                "ev" -> {
                    val sel = _state.value.selected
                    setEv(c.getInt("v").coerceIn(sel?.evMin ?: 0, sel?.evMax ?: 0))
                }
                "lock" -> setLock(c.getBoolean("on"))
                "calibrateBlack" -> calibrateBlack()
                "clearBlack" -> clearBlack()
                "gray" -> setGrayTest(c.getBoolean("on"))
                "zebra" -> setShowZebra(c.getBoolean("on"))
                "debugImage" -> setShowDebug(c.getBoolean("on"))
                else -> return """{"ok":false,"error":"unknown action"}"""
            }
            store.saveCamera(settings())
            """{"ok":true}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace('"', '\'')}"}"""
        }
    }

    fun cameraInfo(): String = CameraInfoDump.dump(app)

    private fun settings() = _state.value.let {
        CameraSettings(
            zoom = it.params.zoom,
            brightness = it.brightness,
            quad = it.quad,
            saturation = it.saturation,
            warmth = it.warmth,
            black = it.black,
            look = it.look,
            fps = it.cameraFps,
            white = it.white,
            smoothing = it.smoothing.name,
        )
    }

    private fun rebuildZones() {
        val sel = engine.selected ?: engine.select()
        val s = _state.value
        _state.update { it.copy(dots = ZoneMapper.centers(strip, s.quad)) }
        if (sel != null) {
            processor.setZones(ZoneMapper.zones(strip, s.quad, s.flip180), strip.totalLeds, sel.width, sel.height)
        }
    }

    /** Camera thread: sample the frame and publish it; a separate paced loop sends it (see [startSendLoop]). */
    private fun onFrame(image: android.media.Image) {
        latestFrame = processor.process(image, engine.selected?.realtimeTimestamps == true)
    }

    /**
     * Sends the newest frame at a fixed rate instead of once per camera frame. The controller gets a steady,
     * modest packet stream (unchanged frames are skipped, with a keep-alive), and only the used LED range.
     */
    private fun startSendLoop(out: DdpSender) {
        sendJob?.cancel()
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            val total = strip.totalLeds
            val used = strip.segments.flatMap { it.indices(total) }
            val from = (used.minOrNull() ?: 0) * 3
            val to = ((used.maxOrNull() ?: (total - 1)) + 1) * 3
            var last: ByteArray? = null
            var lastSent = 0L
            while (isActive) {
                val frame = latestFrame
                val now = System.nanoTime()
                if (frame != null && (last == null || !frame.contentEquals(last) || now - lastSent > KEEPALIVE_NS)) {
                    try {
                        out.send(frame, from, to)
                        val ms = (System.nanoTime() - now) / 1e6f
                        sendMs += (ms - sendMs) * 0.05f
                        if (ms > sendMaxMs) sendMaxMs = ms
                        senderErrors = 0
                    } catch (e: Exception) {
                        if (++senderErrors > 30) {
                            _state.update { it.copy(lightsOn = false, error = "Lost connection to WLED: ${e.message}") }
                            break
                        }
                    }
                    last = frame
                    lastSent = now
                }
                delay(SEND_INTERVAL_MS)
            }
        }
    }

    /**
     * Every 2 s: speed numbers plus, per side, the camera's reading (sRGB 0-255) and the bytes sent for the
     * first/middle/last third of that side. Read with: adb logcat -s CamLight
     */
    private fun logDiagnostics(fps: Int) {
        val raw = processor.rawSnapshot()
        val out = processor.lastOut ?: return
        val sel = engine.selected
        val s = _state.value
        val sb = StringBuilder(
            "stats ${sel?.width}x${sel?.height} in=${fps}fps proc=%.1fms age=%.0fms send=%.2f/%.1fms look=${s.look} smooth=${s.smoothing.label} gamma=${processor.ledGamma} lights=${s.lightsOn}"
                .format(processor.avgProcessMs, processor.lastAgeMs, sendMs, sendMaxMs),
        )
        sendMaxMs = 0f
        sb.append("\n  camera: ${engine.meta} locked=${s.params.lock} ev=${s.params.ev}")
        var k = 0
        strip.segments.forEach { seg ->
            val idx = seg.indices(strip.totalLeds)
            val n = idx.size
            if (n == 0) return@forEach
            sb.append("\n  ${seg.side.label}:")
            for (part in 0..2) {
                val from = n * part / 3
                val to = maxOf(n * (part + 1) / 3, from + 1)
                var r = 0f; var g = 0f; var b = 0f
                var lr = 0; var lg = 0; var lb = 0
                for (i in from until to) {
                    r += raw[(k + i) * 3]; g += raw[(k + i) * 3 + 1]; b += raw[(k + i) * 3 + 2]
                    val led = idx[i]
                    lr += out[led * 3].toInt() and 0xFF; lg += out[led * 3 + 1].toInt() and 0xFF; lb += out[led * 3 + 2].toInt() and 0xFF
                }
                val c = (to - from).toFloat()
                sb.append(" cam(%d,%d,%d)>led(%d,%d,%d)".format(
                    srgb(r / c), srgb(g / c), srgb(b / c), (lr / c).toInt(), (lg / c).toInt(), (lb / c).toInt(),
                ))
            }
            k += n
        }
        Log.i(TAG, sb.toString())
    }

    private fun srgb(linear: Float): Int = (255f * linear.coerceIn(0f, 1f).pow(1f / 2.2f)).toInt()

    private fun onEngineError(message: String) {
        _state.update { it.copy(error = message) }
    }

    override fun onCleared() {
        engine.stop()
        val h = host
        if (sender != null && h != null) {
            sender?.close()
            sender = null
            Thread { runCatching { runBlocking { client.exitLive(h) } } }.start()
        }
        super.onCleared()
    }

    /** Byte to send for a linear-light brightness [lin] given the gamma WLED applies. */
    private fun levelByte(lin: Float): Int =
        (255f * lin.pow(1f / processor.ledGamma)).toInt().coerceIn(0, 255)

    private companion object {
        const val TAG = "CamLight"
        const val MIN_WHITE_LEVEL = 0.05f
        const val PATTERN_SETTLE_MS = 1800L
        const val TV_PAGE_TIMEOUT_MS = 2000L
        const val SEND_INTERVAL_MS = 33L
        const val KEEPALIVE_NS = 400_000_000L
        const val DETECT_LINEAR = 0.4f
        const val CORNER_LINEAR = 0.2f
    }
}
