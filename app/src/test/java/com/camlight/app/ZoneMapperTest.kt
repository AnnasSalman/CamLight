package com.camlight.app

import com.camlight.app.camera.Homography
import com.camlight.app.camera.Pt
import com.camlight.app.camera.Quad
import com.camlight.app.camera.ZoneMapper
import com.camlight.app.model.Side
import com.camlight.app.model.StripConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneMapperTest {
    private val skewed = Quad(Pt(0.30f, 0.25f), Pt(0.72f, 0.30f), Pt(0.70f, 0.74f), Pt(0.28f, 0.68f))

    @Test
    fun homographyMapsUnitSquareCornersToQuad() {
        val h = Homography(skewed)
        fun check(u: Float, v: Float, e: Pt) {
            val p = h.map(u, v)
            assertEquals(e.x, p.x, 1e-4f)
            assertEquals(e.y, p.y, 1e-4f)
        }
        check(0f, 0f, skewed.tl)
        check(1f, 0f, skewed.tr)
        check(1f, 1f, skewed.br)
        check(0f, 1f, skewed.bl)
    }

    @Test
    fun axisAlignedQuadIsLinear() {
        val p = Homography(Quad.DEFAULT).map(0.5f, 0.5f)
        assertEquals(0.5f, p.x, 1e-5f)
        assertEquals(0.5f, p.y, 1e-5f)
    }

    @Test
    fun clockwiseTopRunsLeftToRightAndLeftRunsUp() {
        assertTrue(ZoneMapper.screenPoint(Side.TOP, 0.1f, 0.5f).x < ZoneMapper.screenPoint(Side.TOP, 0.9f, 0.5f).x)
        assertTrue(ZoneMapper.screenPoint(Side.RIGHT, 0.1f, 0.5f).y < ZoneMapper.screenPoint(Side.RIGHT, 0.9f, 0.5f).y)
        assertTrue(ZoneMapper.screenPoint(Side.BOTTOM, 0.1f, 0.5f).x > ZoneMapper.screenPoint(Side.BOTTOM, 0.9f, 0.5f).x)
        assertTrue(ZoneMapper.screenPoint(Side.LEFT, 0.1f, 0.5f).y > ZoneMapper.screenPoint(Side.LEFT, 0.9f, 0.5f).y)
    }

    @Test
    fun zonesCoverEveryUsedLedWithNinePointsInsideTheFrame() {
        val cfg = StripConfig().withCount(0, 10).withCount(1, 6).withCount(2, 10).withCount(3, 6)
        val z = ZoneMapper.zones(cfg, skewed, flip180 = false)
        assertEquals(32, z.leds.size)
        assertEquals(32 * 9 * 2, z.xy.size)
        assertTrue(z.xy.all { it in 0f..1f })
        val flipped = ZoneMapper.zones(cfg, skewed, flip180 = true)
        assertEquals(1f - z.xy[0], flipped.xy[0], 1e-5f)
    }

    @Test
    fun counterClockwiseStripReversesLedOrderAlongEachEdge() {
        // First LED of the first (counter-clockwise) side must sit at the far end of the clockwise edge.
        val cw = StripConfig(clockwise = true).withCount(0, 10)
        val ccw = StripConfig(clockwise = false).withCount(0, 10)
        val a = ZoneMapper.centers(cw, Quad.DEFAULT).first().at
        val b = ZoneMapper.centers(ccw, Quad.DEFAULT).first().at
        assertTrue(a != b)
    }
}
