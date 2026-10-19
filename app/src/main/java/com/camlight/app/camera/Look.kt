package com.camlight.app.camera

/**
 * A light mode (or a test variant): a preset of processing parameters. All values act in linear light after
 * the camera black floor is removed. Saturation and warmth come from the sliders; selecting a mode also
 * resets them (and the speed, if the mode defines one).
 */
data class Look(
    val id: String,
    val name: String,
    val description: String,
    /** Test variants (B-F) are for A/B comparison and shown separately from the modes. */
    val test: Boolean = false,
    val saturation: Float = 1.2f,
    val warmth: Float = 0.7f,
    /** Remove the camera's black floor. False = raw camera values. */
    val blackOn: Boolean = true,
    /** Learn a black floor per LED zone (catches glow, tint or reflections in one area). */
    val perLedBlack: Boolean = false,
    /** Zones darker than [fadeHigh] (linear luma) are dimmed to off, fully off below [fadeLow]. */
    val fadeLow: Float = 0f,
    val fadeHigh: Float = 0f,
    /** Share of color kept in dark zones (1 = all, 0 = they go gray). */
    val darkChroma: Float = 1f,
    /** How strongly blue is reduced in dark/dim zones (0 = not at all). */
    val blueGuard: Float = 0f,
    /** Boost already-saturated colors more than muted ones, so natural scenes are not pushed to primaries. */
    val vibrance: Boolean = false,
    /** Fix out-of-range colors by pulling toward gray (keeps hue) instead of clamping each channel. */
    val gamutClip: Boolean = false,
    /** Apply the near-black knee to luminance (keeps hue) instead of to each channel. */
    val lumaKnee: Boolean = false,
    /** Apply the stored TV-white gains. */
    val useWhiteCal: Boolean = false,
    /** Extra output scale for the mode (e.g. dimmer cinema). */
    val brightnessScale: Float = 1f,
    /** Speed selected with the mode (null = leave as is). */
    val speed: Smoothing? = null,
) {
    companion object {
        val BASIC = Look("basic", "Basic", "The original look: global black level, 120% saturation. Good for bright content.")

        val NATURAL = Look(
            "natural", "Natural",
            "Muted, natural scenes (forests, landscapes): gentler color boost that keeps hue, dark zones lose some color.",
            saturation = 1.05f, warmth = 0.5f, vibrance = true, gamutClip = true, lumaKnee = true,
            darkChroma = 0.5f, fadeLow = 0.008f, fadeHigh = 0.06f, useWhiteCal = true,
        )

        val ROOM = Look(
            "room", "Room light",
            "For rooms with lamps on: per-zone black level, dark zones keep little color and less blue. Lock exposure & color, then calibrate black and white with the lamps on.",
            saturation = 1.1f, warmth = 0.7f, perLedBlack = true, gamutClip = true, lumaKnee = true,
            darkChroma = 0.35f, blueGuard = 0.5f, fadeLow = 0.012f, fadeHigh = 0.12f, useWhiteCal = true,
        )

        val CINEMA = Look(
            "cinema", "Cinema",
            "Dim and calm for dark movies: dark scenes fade to off, softer colors, 70% brightness, smooth speed.",
            saturation = 1.1f, warmth = 0.8f, vibrance = true, gamutClip = true, lumaKnee = true,
            darkChroma = 0.5f, fadeLow = 0.02f, fadeHigh = 0.15f, useWhiteCal = true,
            brightnessScale = 0.7f, speed = Smoothing.SMOOTH,
        )

        val VIVID = Look(
            "vivid", "Vivid",
            "Punchy colors for games, sports and animation: 140% saturation, fast speed.",
            saturation = 1.4f, warmth = 0.5f, speed = Smoothing.FAST,
        )

        val MODES = listOf(BASIC, NATURAL, ROOM, CINEMA, VIVID)

        val TESTS = listOf(
            Look("B", "Dark fade", "Dim zones fade to off instead of glowing.", test = true, fadeLow = 0.01f, fadeHigh = 0.10f),
            Look("C", "Dark fade + gray", "As B, and dark zones lose their color so they go dim gray instead of tinted.",
                test = true, fadeLow = 0.01f, fadeHigh = 0.10f, darkChroma = 0.3f),
            Look("D", "Blue guard", "As B, and blue is reduced in dark zones.", test = true,
                fadeLow = 0.01f, fadeHigh = 0.10f, blueGuard = 0.6f),
            Look("E", "Per-zone black", "As B, but each zone learns its own black floor.", test = true,
                fadeLow = 0.01f, fadeHigh = 0.10f, perLedBlack = true),
            Look("F", "Raw", "No black removal or dark handling, saturation 100%, no warmth. Shows what the camera really sees.",
                test = true, saturation = 1f, warmth = 0f, blackOn = false),
        )

        val ALL = MODES + TESTS

        /** Accepts current ids and the old single-letter id "A" (now Basic). */
        fun byId(id: String?): Look = ALL.firstOrNull { it.id == id } ?: BASIC
    }
}

/** How quickly the LEDs follow changes. A lower base means smoother but laggier. */
enum class Smoothing(val label: String, val base: Float) {
    FAST("Fast", 0.7f),
    NORMAL("Normal", 0.3f),
    SMOOTH("Smooth", 0.15f),
}
