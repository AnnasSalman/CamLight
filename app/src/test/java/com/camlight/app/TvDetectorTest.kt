package com.camlight.app

import com.camlight.app.camera.Pt
import com.camlight.app.camera.Quad
import com.camlight.app.camera.TvDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class TvDetectorTest {
    private val w = 640
    private val h = 360

    // TV silhouette in pixels, slightly skewed (phone not perfectly square to the TV).
    private val tl = Pt(220f, 100f)
    private val tr = Pt(420f, 104f)
    private val br = Pt(418f, 214f)
    private val bl = Pt(221f, 212f)

    private fun inside(x: Float, y: Float, q: List<Pt>): Boolean {
        var pos = 0
        var neg = 0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val c = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            if (c > 0) pos++ else neg++
        }
        return pos == 0 || neg == 0
    }

    /** Glow ring of ~40 px around the TV, zero inside the TV and far away. [noBottom] removes the glow below. */
    private fun synth(noBottom: Boolean = false): FloatArray {
        val quad = listOf(tl, tr, br, bl)
        val cx = quad.sumOf { it.x.toDouble() }.toFloat() / 4
        val cy = quad.sumOf { it.y.toDouble() }.toFloat() / 4
        val big = quad.map { Pt(cx + (it.x - cx) * 1.45f, cy + (it.y - cy) * 1.7f) }
        val d = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val xf = x.toFloat()
            val yf = y.toFloat()
            if (inside(xf, yf, quad)) continue
            if (!inside(xf, yf, big)) continue
            if (noBottom && yf > (bl.y + br.y) / 2) continue
            d[y * w + x] = 90f
        }
        return d
    }

    private fun assertCorner(expected: Pt, actual: Pt, tol: Float = 2.5f) {
        val dx = expected.x - actual.x * w
        val dy = expected.y - actual.y * h
        assertEquals("corner off by ($dx, $dy)", 0.0, hypot(dx.toDouble(), dy.toDouble()), tol.toDouble())
    }

    @Test
    fun findsSkewedTvFromGlowRing() {
        val out = TvDetector.detect(synth(), w, h)
        val r = out.result
        assertNotNull(out.message, r)
        assertEquals(4, r!!.sidesFound)
        assertCorner(tl, r.silhouette.tl)
        assertCorner(tr, r.silhouette.tr)
        assertCorner(br, r.silhouette.br)
        assertCorner(bl, r.silhouette.bl)
    }

    @Test
    fun picturePolygonIsInsideTheSilhouette() {
        val r = TvDetector.detect(synth(), w, h).result!!
        assertTrue(r.picture.tl.x > r.silhouette.tl.x && r.picture.tl.y > r.silhouette.tl.y)
        assertTrue(r.picture.br.x < r.silhouette.br.x && r.picture.br.y < r.silhouette.br.y)
    }

    @Test
    fun infersMissingBottomEdgeFromAspectRatio() {
        // The synthetic TV is 200x110 px (~16:9 would be 112), so allow a few pixels of inference error.
        val out = TvDetector.detect(synth(noBottom = true), w, h)
        val r = out.result
        assertNotNull(out.message, r)
        assertEquals(3, r!!.sidesFound)
        assertCorner(tl, r.silhouette.tl)
        assertCorner(tr, r.silhouette.tr)
        assertCorner(bl, r.silhouette.bl, 6f)
        assertCorner(br, r.silhouette.br, 6f)
    }

    @Test
    fun failsOnEmptyFrame() {
        assertNull(TvDetector.detect(FloatArray(w * h), w, h).result)
    }
}
