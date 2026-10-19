package com.camlight.app

import com.camlight.app.model.Corner
import com.camlight.app.model.Preview
import com.camlight.app.model.Side
import com.camlight.app.model.StripConfig
import com.camlight.app.model.StripRenderer
import com.camlight.app.wled.DdpSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StripTest {
    @Test
    fun sideOrderFollowsCornerAndDirection() {
        val cw = listOf(Side.TOP, Side.RIGHT, Side.BOTTOM, Side.LEFT)
        assertEquals(cw, StripConfig.sideOrder(Corner.TOP_LEFT, true))
        assertEquals(listOf(Side.LEFT, Side.TOP, Side.RIGHT, Side.BOTTOM), StripConfig.sideOrder(Corner.BOTTOM_LEFT, true))
        assertEquals(listOf(Side.LEFT, Side.BOTTOM, Side.RIGHT, Side.TOP), StripConfig.sideOrder(Corner.TOP_LEFT, false))
        assertEquals(listOf(Side.BOTTOM, Side.RIGHT, Side.TOP, Side.LEFT), StripConfig.sideOrder(Corner.BOTTOM_LEFT, false))
    }

    @Test
    fun startsAreDerivedAndRipple() {
        var c = StripConfig(gaps = listOf(2, 0, 5, 0)).withCount(0, 10).withCount(1, 20).withCount(2, 30)
        val s = c.segments
        assertEquals(2, s[0].start)
        assertEquals(12, s[1].start)
        assertEquals(37, s[2].start) // 12 + 20 + gap 5
        c = c.withCount(0, 15) // earlier side grows: later sides shift
        assertEquals(17, c.segments[1].start)
        assertEquals(42, c.segments[2].start)
    }

    @Test
    fun countsAreClampedToTheStrip() {
        val c = StripConfig(totalLeds = 100).withCount(0, 80).withCount(1, 50)
        assertEquals(80, c.counts[0])
        assertEquals(20, c.counts[1])
        assertEquals(0, c.withCount(0, 500).counts[1])
        assertEquals(30, c.withTotal(30).counts.sum())
    }

    @Test
    fun configJsonRoundTrip() {
        val cfg = StripConfig(startCorner = Corner.TOP_RIGHT, clockwise = false, configured = true)
            .withCount(0, 12).withGap(1, 3).withCount(1, 7)
        assertEquals(cfg, StripConfig.fromJson(cfg.toJson()))
        assertEquals(StripConfig(), StripConfig.fromJson("garbage"))
    }

    @Test
    fun editFrameShowsDimPastRedEndAndNothingElse() {
        val cfg = StripConfig().withCount(0, 5).withCount(1, 4)
        val f = StripRenderer.render(cfg, Preview.EditSide(1), 0)
        assertEquals(300 * 3, f.size)
        fun px(i: Int) = Triple(f[i * 3].toInt() and 0xFF, f[i * 3 + 1].toInt() and 0xFF, f[i * 3 + 2].toInt() and 0xFF)
        assertTrue(px(2).let { it.first + it.second + it.third } > 0) // earlier side, dim
        val white = px(5)
        assertTrue(white.first > 0 && white.first == white.second && white.second == white.third)
        assertTrue(px(8).let { it.first > 0 && it.second == 0 && it.third == 0 }) // red end
        assertEquals(Triple(0, 0, 0), px(9))
    }

    @Test
    fun stripStartLightsFirstThreeGreen() {
        val f = StripRenderer.render(StripConfig(), Preview.StripStart, 0)
        for (i in 0..2) assertTrue(f[i * 3 + 1].toInt() != 0 && f[i * 3].toInt() == 0)
        assertEquals(0, f[3 * 3 + 1].toInt())
    }

    @Test
    fun ddpHeaderLayout() {
        val b = ByteArray(10)
        DdpSender.writeHeader(b, offset = 1440, len = 900, push = true, seq = 3)
        assertEquals(0x41, b[0].toInt())
        assertEquals(3, b[1].toInt())
        assertEquals(1440, ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF))
        assertEquals(900, ((b[8].toInt() and 0xFF) shl 8) or (b[9].toInt() and 0xFF))
    }
}
