package com.camlight.app

import com.camlight.app.camera.Look
import com.camlight.app.camera.Smoothing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    @Test
    fun modesAndTestVariantsHaveUniqueIds() {
        assertEquals(Look.ALL.size, Look.ALL.map { it.id }.toSet().size)
        assertTrue(Look.MODES.none { it.test })
        assertTrue(Look.TESTS.all { it.test })
    }

    @Test
    fun basicIsTheOriginalLookAndTheLegacyIdMapsToIt() {
        val basic = Look.BASIC
        assertEquals("Basic", basic.name)
        assertEquals(1.2f, basic.saturation, 0f)
        assertEquals(0.7f, basic.warmth, 0f)
        assertFalse(basic.perLedBlack || basic.gamutClip || basic.lumaKnee || basic.vibrance || basic.useWhiteCal)
        assertEquals(0f, basic.fadeHigh, 0f)
        assertEquals(basic, Look.byId("A"))
        assertEquals(basic, Look.byId(null))
        assertEquals(basic, Look.byId("unknown"))
    }

    @Test
    fun newModesCarryTheirIntendedBehavior() {
        assertTrue(Look.NATURAL.gamutClip && Look.NATURAL.vibrance && Look.NATURAL.lumaKnee)
        assertTrue(Look.NATURAL.saturation < Look.BASIC.saturation)
        assertTrue(Look.ROOM.perLedBlack && Look.ROOM.blueGuard > 0f && Look.ROOM.useWhiteCal)
        assertTrue(Look.CINEMA.brightnessScale < 1f && Look.CINEMA.fadeHigh > Look.NATURAL.fadeHigh)
        assertEquals(Smoothing.SMOOTH, Look.CINEMA.speed)
        assertTrue(Look.VIVID.saturation > Look.BASIC.saturation)
        assertEquals(Smoothing.FAST, Look.VIVID.speed)
    }
}
