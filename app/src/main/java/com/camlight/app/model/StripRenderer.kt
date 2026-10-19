package com.camlight.app.model

/** What the real strip should show for the current wizard screen. */
sealed interface Preview {
    /** Mark the physical start of the strip. */
    data object StripStart : Preview

    /** Editing the side at strip position [pos]: earlier sides dim, current run white, end marker red. */
    data class EditSide(val pos: Int) : Preview

    /** Every side in its own color. */
    data object AllSides : Preview

    /** A light running around the whole loop in strip order. */
    data object Chase : Preview

    data object Off : Preview
}

/** Builds the RGB frame (3 bytes per LED) shown on the real strip while configuring. */
object StripRenderer {
    private const val LEVEL = 0.35f // keep setup brightness modest
    private const val DIM = 0.12f
    private const val TAIL = 8
    const val START_GREEN = 0x00FF00
    const val END_RED = 0xFF0000

    fun render(config: StripConfig, preview: Preview, tick: Int): ByteArray {
        val frame = ByteArray(config.totalLeds * 3)
        val segs = config.segments
        when (preview) {
            Preview.Off -> Unit
            Preview.StripStart -> for (i in 0 until 3) put(frame, i, START_GREEN, 1f)
            Preview.AllSides -> segs.forEach { s -> s.indices(config.totalLeds).forEach { put(frame, it, s.side.rgb, 1f) } }
            is Preview.EditSide -> {
                for (p in 0 until preview.pos) {
                    segs[p].indices(config.totalLeds).forEach { put(frame, it, segs[p].side.rgb, DIM / LEVEL) }
                }
                val idx = segs[preview.pos].indices(config.totalLeds)
                idx.forEach { put(frame, it, 0xFFFFFF, 1f) }
                if (idx.isNotEmpty()) put(frame, idx.last(), END_RED, 1f)
            }
            Preview.Chase -> {
                val idx = segs.flatMap { it.indices(config.totalLeds) }
                if (idx.isNotEmpty()) {
                    val head = tick % idx.size
                    for (t in 0 until TAIL) {
                        val i = head - t
                        if (i >= 0) put(frame, idx[i], 0xFFFFFF, 1f - t / TAIL.toFloat())
                    }
                }
            }
        }
        return frame
    }

    private fun put(frame: ByteArray, led: Int, rgb: Int, scale: Float) {
        val o = led * 3
        if (led < 0 || o + 2 >= frame.size) return
        val f = LEVEL * scale
        frame[o] = ((rgb shr 16 and 0xFF) * f).toInt().toByte()
        frame[o + 1] = ((rgb shr 8 and 0xFF) * f).toInt().toByte()
        frame[o + 2] = ((rgb and 0xFF) * f).toInt().toByte()
    }
}
