package com.camlight.app.camera

import android.graphics.Bitmap
import android.media.Image
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/** Zebra grid: which coarse cells of the frame are blown out (clipped). */
class Zebra(val cols: Int, val rows: Int, val clipped: BooleanArray)

/**
 * Turns a YUV frame into one RGB value per LED.
 *
 * Per LED (all in linear light): mean of a few sample points -> subtract the camera's black floor ->
 * soft knee near black -> saturation -> brightness and LED white balance -> encode with the LED gamma ->
 * adaptive temporal smoothing -> temporal dithering to 8 bits.
 */
class AmbientProcessor {
    // ── look settings (any thread) ──
    @Volatile var brightness = 0.8f
    @Volatile var look: Look = Look.ALL[0]
    /** Base smoothing strength 0..1 (higher = follows faster, noisier). */
    @Volatile var smoothBase = 0.3f
    @Volatile var saturation = 1.2f
    /** 0 = raw LED white (cool), 1 = strongly warmed. Scales green and blue down. */
    @Volatile var warmth = 0.7f
    /** WLED's color gamma (1.0 when it is switched off). We encode so that WLED's curve cancels out. */
    @Volatile var ledGamma = 1.0f
        set(v) {
            field = v.coerceIn(1f, 3.5f)
            encode = buildEncode(field)
        }
    /** Black floor measured by the user with the TV black; null = learn it automatically. */
    @Volatile var blackManual: FloatArray? = null

    /** True when the buffer is upside down relative to the display (phone in the other landscape). */
    @Volatile var flip = false

    @Volatile var previewBitmap: Bitmap? = null
        private set
    @Volatile var gray: ByteArray? = null
        private set
    @Volatile var grayVersion = 0
        private set
    private var lastPreviewNs = 0L
    private val previewBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val previewExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "camlight-preview").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var pyBuf = ByteArray(0)
    private var puBuf = ByteArray(0)
    private var pvBuf = ByteArray(0)

    private class Layout(val leds: IntArray, val px: IntArray, val py: IntArray, val perLed: Int)

    @Volatile private var layout: Layout? = null
    @Volatile private var totalLeds = 0
    private var state = FloatArray(0) // smoothed value in the LED encoding, 0..255
    private var err = FloatArray(0) // dither error carry
    private var raw = FloatArray(0) // per-LED linear mean, 3 per LED
    private val blackAuto = FloatArray(3)
    private var blackLed = FloatArray(0)

    /** Latest per-LED linear camera values (3 per LED, in zone order) and the bytes sent, for diagnostics. */
    @Volatile var lastOut: ByteArray? = null
        private set
    fun rawSnapshot(): FloatArray = raw.copyOf()
    @Volatile private var encode = buildEncode(1f)

    // black capture
    @Volatile private var captureLeft = 0
    private var captureTotal = 0
    private var captureScale = 1.0
    private val captureSum = DoubleArray(3)
    @Volatile var captureResult: FloatArray? = null
        private set

    @Volatile var zebra: Zebra? = null
        private set
    @Volatile var lastAgeMs = 0f
        private set
    @Volatile var avgProcessMs = 0f
        private set
    @Volatile var frames = 0L
        private set
    /** Current black floor in use (for the UI). */
    @Volatile var blackInUse = FloatArray(3)
        private set

    fun setZones(zones: Zones, total: Int, width: Int, height: Int) {
        val n = zones.xy.size / 2
        val px = IntArray(n) { (zones.xy[it * 2] * (width - 1)).toInt().coerceIn(0, width - 1) }
        val py = IntArray(n) { (zones.xy[it * 2 + 1] * (height - 1)).toInt().coerceIn(0, height - 1) }
        if (total != totalLeds || state.size != total * 3) {
            state = FloatArray(total * 3)
            err = FloatArray(total * 3)
        }
        raw = FloatArray(zones.leds.size * 3)
        totalLeds = total
        layout = Layout(zones.leds, px, py, zones.perLed)
    }

    /**
     * Average the next [frames] frames' zone colors (linear light, times [scale]). Used for the camera's
     * "black" (TV off or black, scale slightly above 1 for margin) and for the TV's white (scale 1).
     */
    fun beginCapture(frames: Int, scale: Double) {
        captureResult = null
        captureSum.fill(0.0)
        captureScale = scale
        captureTotal = frames
        captureLeft = frames
    }

    /** White balance gains measured with the TV showing white (green = 1); null = not calibrated. */
    @Volatile var whiteGains: FloatArray? = null

    val captureProgress: Float
        get() = if (captureTotal == 0) 1f else 1f - captureLeft.toFloat() / captureTotal

    fun process(image: Image, realtimeTimestamps: Boolean): ByteArray {
        val t0 = System.nanoTime()
        val out = ByteArray(totalLeds * 3)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yb = y.buffer
        val ub = u.buffer
        val vb = v.buffer
        val yRow = y.rowStride
        val uRow = u.rowStride
        val uPix = u.pixelStride
        val vRow = v.rowStride
        val vPix = v.pixelStride

        val l = layout
        if (l != null && state.size == totalLeds * 3 && raw.size == l.leds.size * 3) {
            val n = l.leds.size
            val min = floatArrayOf(1f, 1f, 1f)
            val mean = DoubleArray(3)
            var s = 0
            for (k in 0 until n) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (p in 0 until l.perLed) {
                    val x = l.px[s]
                    val yy = l.py[s]
                    s++
                    val yv = (yb.get(yy * yRow + x).toInt() and 0xFF).toFloat()
                    val uv = (ub.get((yy shr 1) * uRow + (x shr 1) * uPix).toInt() and 0xFF) - 128f
                    val vv = (vb.get((yy shr 1) * vRow + (x shr 1) * vPix).toInt() and 0xFF) - 128f
                    r += LINEAR[clamp255(yv + 1.402f * vv)]
                    g += LINEAR[clamp255(yv - 0.344136f * uv - 0.714136f * vv)]
                    b += LINEAR[clamp255(yv + 1.772f * uv)]
                }
                val o = k * 3
                raw[o] = r / l.perLed
                raw[o + 1] = g / l.perLed
                raw[o + 2] = b / l.perLed
                for (c in 0..2) {
                    if (raw[o + c] < min[c]) min[c] = raw[o + c]
                    mean[c] += raw[o + c].toDouble()
                }
            }
            for (c in 0..2) mean[c] /= n.toDouble()
            trackBlack(min, mean)

            val black = blackManual ?: blackAuto
            blackInUse = black
            val lk = look
            if (lk.perLedBlack && blackLed.size != n * 3) blackLed = FloatArray(n * 3)
            val gain = brightness * lk.brightnessScale
            val sat = saturation
            val w = warmth
            val wbR = 1f
            val wbG = 1f - 0.17f * w
            val wbB = 1f - 0.64f * w
            val enc = encode
            val encMax = enc.size - 1
            val base = smoothBase
            val wg = if (lk.useWhiteCal) whiteGains else null

            for (k in 0 until n) {
                val o = k * 3
                var r = raw[o]
                var g = raw[o + 1]
                var b = raw[o + 2]
                if (lk.blackOn) {
                    if (lk.perLedBlack) learnLedBlack(o, r, g, b)
                    val fr = if (lk.perLedBlack) blackLed[o] else black[0]
                    val fg = if (lk.perLedBlack) blackLed[o + 1] else black[1]
                    val fb = if (lk.perLedBlack) blackLed[o + 2] else black[2]
                    if (lk.lumaKnee) { r = lin(r, fr); g = lin(g, fg); b = lin(b, fb) }
                    else { r = shape(r, fr); g = shape(g, fg); b = shape(b, fb) }
                }
                if (wg != null) { r *= wg[0]; g *= wg[1]; b *= wg[2] }
                var luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                if (lk.lumaKnee && luma < KNEE && luma > 0f) {
                    // Soft start on brightness only, so the hue of dim zones is left alone.
                    val ks = luma / KNEE
                    r *= ks; g *= ks; b *= ks; luma *= ks
                }
                val cs = lk.darkChroma + (1f - lk.darkChroma) * smooth(luma, 0.02f, 0.25f)
                val bg = 1f - lk.blueGuard * (1f - smooth(luma, 0.02f, 0.30f))
                val fade = if (lk.fadeHigh > lk.fadeLow) smooth(luma, lk.fadeLow, lk.fadeHigh) else 1f
                var s = sat * cs
                if (lk.vibrance && s > 1f) {
                    // Boost muted colors little and saturated colors more.
                    val mx = max(r, max(g, b))
                    val mn = kotlin.math.min(r, kotlin.math.min(g, b))
                    val m = if (mx > 0f) (mx - mn) / mx else 0f
                    s = 1f + (s - 1f) * m
                }
                var r2 = luma + (r - luma) * s
                var g2 = luma + (g - luma) * s
                var b2 = luma + (b - luma) * s
                if (lk.gamutClip && luma > 0f) {
                    // Pull out-of-range colors toward gray along the same hue instead of clamping channels.
                    val mn = kotlin.math.min(r2, kotlin.math.min(g2, b2))
                    val mx = max(r2, max(g2, b2))
                    var t = 1f
                    if (mn < 0f) t = kotlin.math.min(t, luma / (luma - mn))
                    if (mx > 1f && luma < 1f) t = kotlin.math.min(t, (1f - luma) / (mx - luma))
                    if (t < 1f) {
                        r2 = luma + (r2 - luma) * t; g2 = luma + (g2 - luma) * t; b2 = luma + (b2 - luma) * t
                    }
                }
                val tr = enc[lut(r2.coerceIn(0f, 1f) * gain * wbR * fade, encMax)]
                val tg = enc[lut(g2.coerceIn(0f, 1f) * gain * wbG * fade, encMax)]
                val tb = enc[lut(b2.coerceIn(0f, 1f) * gain * wbB * fade * bg, encMax)]

                val lo = l.leds[k] * 3
                val delta = max(abs(tr - state[lo]), max(abs(tg - state[lo + 1]), abs(tb - state[lo + 2])))
                // Small changes (sensor noise) are smoothed hard, big changes pass almost unfiltered.
                val alpha = (base + delta / 255f * 2.5f).coerceAtMost(1f)
                step(lo, tr, alpha, out)
                step(lo + 1, tg, alpha, out)
                step(lo + 2, tb, alpha, out)
            }
        }

        lastOut = out
        frames++
        if (frames % ZEBRA_EVERY == 0L) zebra = computeZebra(yb, yRow, image.width, image.height)
        if (t0 - lastPreviewNs >= PREVIEW_INTERVAL_NS && previewBusy.compareAndSet(false, true)) {
            lastPreviewNs = t0
            // Cheap bulk copy here; the slow per-pixel conversion runs on a worker so the camera thread never stalls.
            pyBuf = copyOf(yb, pyBuf)
            puBuf = copyOf(ub, puBuf)
            pvBuf = copyOf(vb, pvBuf)
            val (ya, ua, va) = Triple(pyBuf, puBuf, pvBuf)
            val w = image.width
            val h = image.height
            val strides = intArrayOf(yRow, uRow, uPix, vRow, vPix)
            previewExecutor.execute {
                try {
                    buildPreview(ya, strides[0], ua, strides[1], strides[2], va, strides[3], strides[4], w, h)
                } finally {
                    previewBusy.set(false)
                }
            }
        }
        if (realtimeTimestamps) {
            lastAgeMs = (android.os.SystemClock.elapsedRealtimeNanos() - image.timestamp) / 1e6f
        }
        avgProcessMs += ((System.nanoTime() - t0) / 1e6f - avgProcessMs) * 0.05f
        return out
    }

    /** Black removal plus a soft start so near-black fades to off instead of glowing a dim tint. */
    private fun shape(v: Float, black: Float): Float {
        val x = ((v - black) / (1f - black)).coerceAtLeast(0f)
        return if (x < KNEE) x * x / KNEE else x
    }

    private fun copyOf(src: java.nio.ByteBuffer, reuse: ByteArray): ByteArray {
        val n = src.remaining()
        val dst = if (reuse.size >= n) reuse else ByteArray(n)
        src.duplicate().get(dst, 0, n)
        return dst
    }

    /** Black removal without the per-channel knee. */
    private fun lin(v: Float, black: Float) = ((v - black) / (1f - black)).coerceAtLeast(0f)

    private fun lut(v: Float, max: Int) = (v.coerceIn(0f, 1f) * max).toInt()

    private fun smooth(x: Float, a: Float, b: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Each zone's own black floor: drops at once to a darker reading, creeps up slowly. */
    private fun learnLedBlack(o: Int, r: Float, g: Float, b: Float) {
        learn(o, r); learn(o + 1, g); learn(o + 2, b)
    }

    private fun learn(i: Int, v: Float) {
        val f = blackLed[i]
        blackLed[i] = (if (v < f) v else f + (v - f) * LED_BLACK_RISE).coerceAtMost(LED_BLACK_CAP)
    }

    /** One channel: smooth toward [target], then dither to 8 bits (error carried to the next frame). */
    private fun step(i: Int, target: Float, alpha: Float, out: ByteArray) {
        val st = state[i] + (target - state[i]) * alpha
        state[i] = if (target == 0f && st < 0.05f) 0f else st
        if (state[i] == 0f) {
            err[i] = 0f
            out[i] = 0
        } else {
            val dv = state[i] + err[i]
            val q = floor(dv + 0.5f).toInt().coerceIn(0, 255)
            err[i] = (dv - q).coerceIn(-1f, 1f)
            out[i] = q.toByte()
        }
    }

    /** Learns the camera's black floor per channel and handles an explicit "calibrate black" capture. */
    private fun trackBlack(min: FloatArray, mean: DoubleArray) {
        for (c in 0..2) {
            if (min[c] < blackAuto[c]) blackAuto[c] = min[c]
            else blackAuto[c] += (min[c] - blackAuto[c]) * BLACK_RISE
            blackAuto[c] = blackAuto[c].coerceAtMost(BLACK_AUTO_CAP)
        }
        if (captureLeft > 0) {
            for (c in 0..2) captureSum[c] += mean[c]
            captureLeft--
            if (captureLeft == 0) {
                captureResult = FloatArray(3) { ((captureSum[it] / captureTotal) * captureScale).toFloat() }
            }
        }
    }

    /** Gray steps (5%, 20%, 60% linear light) through the same output stage, to judge LED neutrality by eye. */
    fun grayTestFrame(usedLeds: List<Int>, total: Int): ByteArray {
        val out = ByteArray(total * 3)
        val w = warmth
        val wb = floatArrayOf(1f, 1f - 0.17f * w, 1f - 0.64f * w)
        val enc = encode
        val levels = floatArrayOf(0.05f, 0.2f, 0.6f)
        usedLeds.forEachIndexed { i, led ->
            val lvl = levels[(i * 3 / usedLeds.size).coerceIn(0, 2)]
            for (c in 0..2) {
                val lin = (lvl * wb[c]).coerceIn(0f, 1f)
                out[led * 3 + c] = enc[(lin * (enc.size - 1)).toInt()].toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /**
     * Downscaled copy of the frame (what the user sees, and what TV detection analyses). It is already
     * flipped into display orientation, so overlay coordinates and analysis share one coordinate system.
     */
    private fun buildPreview(
        yb: ByteArray, yRow: Int,
        ub: ByteArray, uRow: Int, uPix: Int,
        vb: ByteArray, vRow: Int, vPix: Int,
        w: Int, h: Int,
    ) {
        val sx = (w / PREVIEW_W).coerceAtLeast(1)
        val sy = (h / PREVIEW_H).coerceAtLeast(1)
        val flipped = flip
        val px = IntArray(PREVIEW_W * PREVIEW_H)
        val g = ByteArray(PREVIEW_W * PREVIEW_H)
        for (j in 0 until PREVIEW_H) for (i in 0 until PREVIEW_W) {
            var x = (i * sx + sx / 2).coerceAtMost(w - 1)
            var y = (j * sy + sy / 2).coerceAtMost(h - 1)
            if (flipped) { x = w - 1 - x; y = h - 1 - y }
            val yv = (yb.get(y * yRow + x).toInt() and 0xFF).toFloat()
            val uv = (ub.get((y shr 1) * uRow + (x shr 1) * uPix).toInt() and 0xFF) - 128f
            val vv = (vb.get((y shr 1) * vRow + (x shr 1) * vPix).toInt() and 0xFF) - 128f
            val k = j * PREVIEW_W + i
            g[k] = yv.toInt().toByte()
            px[k] = (0xFF shl 24) or
                (clamp255(yv + 1.402f * vv) shl 16) or
                (clamp255(yv - 0.344136f * uv - 0.714136f * vv) shl 8) or
                clamp255(yv + 1.772f * uv)
        }
        previewBitmap = Bitmap.createBitmap(px, PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888)
        gray = g
        grayVersion++
    }

    private fun computeZebra(yb: java.nio.ByteBuffer, yRow: Int, w: Int, h: Int): Zebra {
        val cols = 32
        val rows = 18
        val cells = BooleanArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            var clipped = 0
            for (dy in 0..1) for (dx in 0..1) {
                val x = ((c + 0.25f + dx * 0.5f) / cols * (w - 1)).toInt()
                val y = ((r + 0.25f + dy * 0.5f) / rows * (h - 1)).toInt()
                if ((yb.get(y * yRow + x).toInt() and 0xFF) >= CLIP_LEVEL) clipped++
            }
            cells[r * cols + c] = clipped >= 3
        }
        return Zebra(cols, rows, cells)
    }

    private fun clamp255(v: Float) = v.toInt().coerceIn(0, 255)

    companion object {
        const val PREVIEW_W = 640
        const val PREVIEW_H = 360
        private const val PREVIEW_INTERVAL_NS = 100_000_000L
        private const val ZEBRA_EVERY = 6L
        const val CLIP_LEVEL = 250
        private const val KNEE = 0.01f
        private const val BLACK_RISE = 0.005f
        private const val LED_BLACK_RISE = 0.01f
        private const val LED_BLACK_CAP = 0.05f
        private const val BLACK_AUTO_CAP = 0.03f
        const val BLACK_MANUAL_CAP = 0.08f

        val LINEAR = FloatArray(256) { i ->
            val c = i / 255f
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }

        /** Linear light 0..1 -> LED byte value 0..255 for a WLED color gamma [g] (1.0 = WLED gamma off). */
        fun buildEncode(g: Float) = FloatArray(4096) { i -> 255f * (i / 4095f).pow(1f / g) }
    }
}
