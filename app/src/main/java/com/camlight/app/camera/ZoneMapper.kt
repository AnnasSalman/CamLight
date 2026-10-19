package com.camlight.app.camera

import com.camlight.app.model.Side
import com.camlight.app.model.StripConfig

data class Pt(val x: Float, val y: Float)

/** The TV picture's four corners in normalized camera-preview coordinates (0..1, origin top-left). */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    fun toList() = listOf(tl, tr, br, bl)

    fun with(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        else -> copy(bl = p)
    }

    companion object {
        val DEFAULT = Quad(Pt(0.3f, 0.3f), Pt(0.7f, 0.3f), Pt(0.7f, 0.7f), Pt(0.3f, 0.7f))
    }
}

/** Projective map from the unit square (screen coordinates) to a camera-space quad (Heckbert). */
class Homography(q: Quad) {
    private val a: Float
    private val b: Float
    private val c: Float
    private val d: Float
    private val e: Float
    private val f: Float
    private val g: Float
    private val h: Float

    init {
        val (x0, y0) = q.tl
        val (x1, y1) = q.tr
        val (x2, y2) = q.br
        val (x3, y3) = q.bl
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        if (sx == 0f && sy == 0f) {
            g = 0f; h = 0f
            a = x1 - x0; b = x2 - x1; c = x0
            d = y1 - y0; e = y2 - y1; f = y0
        } else {
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val det = dx1 * dy2 - dx2 * dy1
            g = (sx * dy2 - dx2 * sy) / det
            h = (dx1 * sy - sx * dy1) / det
            a = x1 - x0 + g * x1; b = x3 - x0 + h * x3; c = x0
            d = y1 - y0 + g * y1; e = y3 - y0 + h * y3; f = y0
        }
    }

    fun map(u: Float, v: Float): Pt {
        val w = g * u + h * v + 1f
        return Pt((a * u + b * v + c) / w, (d * u + e * v + f) / w)
    }
}

/** Sample points per LED in normalized camera coordinates (x,y interleaved). */
class Zones(val leds: IntArray, val xy: FloatArray, val perLed: Int)

data class ZoneDot(val at: Pt, val rgb: Int)

object ZoneMapper {
    const val GRID = 3
    const val DEPTH_V = 0.14f // how far into the picture a top/bottom LED samples (fraction of height)
    const val DEPTH_U = 0.08f // same for left/right LEDs (fraction of width)
    const val INSET = 0.10f // skip this fraction of the depth next to the bezel

    /** Screen point (unit square, origin top-left) for edge position [a] (clockwise 0..1) and depth [d] (0..1). */
    fun screenPoint(side: Side, a: Float, d: Float): Pt {
        val depth = INSET + d * (1f - INSET)
        return when (side) {
            Side.TOP -> Pt(a, depth * DEPTH_V)
            Side.RIGHT -> Pt(1f - depth * DEPTH_U, a)
            Side.BOTTOM -> Pt(1f - a, 1f - depth * DEPTH_V)
            Side.LEFT -> Pt(depth * DEPTH_U, 1f - a)
        }
    }

    /** Edge position of LED slot [i] of [n] in strip order; counter-clockwise strips run backwards. */
    private fun slot(i: Int, n: Int, reversed: Boolean) = if (reversed) n - 1 - i else i

    fun zones(config: StripConfig, quad: Quad, flip180: Boolean): Zones {
        val h = Homography(quad)
        val leds = ArrayList<Int>()
        val xy = ArrayList<Float>()
        for (seg in config.segments) {
            val idx = seg.indices(config.totalLeds)
            val n = idx.size
            idx.forEachIndexed { i, led ->
                val k = slot(i, n, seg.reversed)
                leds += led
                for (j in 0 until GRID) for (m in 0 until GRID) {
                    val a = (k + (j + 0.5f) / GRID) / n
                    val d = (m + 0.5f) / GRID
                    val sp = screenPoint(seg.side, a, d)
                    val p = h.map(sp.x, sp.y)
                    xy += (if (flip180) 1f - p.x else p.x).coerceIn(0f, 1f)
                    xy += (if (flip180) 1f - p.y else p.y).coerceIn(0f, 1f)
                }
            }
        }
        return Zones(leds.toIntArray(), xy.toFloatArray(), GRID * GRID)
    }

    /** One dot per LED at the middle of its sample cell, in preview coordinates, for the overlay. */
    fun centers(config: StripConfig, quad: Quad): List<ZoneDot> {
        val h = Homography(quad)
        return config.segments.flatMap { seg ->
            val n = seg.indices(config.totalLeds).size
            (0 until n).map { i ->
                val k = slot(i, n, seg.reversed)
                val sp = screenPoint(seg.side, (k + 0.5f) / n, 0.5f)
                ZoneDot(h.map(sp.x, sp.y), seg.side.rgb)
            }
        }
    }
}
