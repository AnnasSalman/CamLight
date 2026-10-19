package com.camlight.app.model

import org.json.JSONArray
import org.json.JSONObject

enum class Side(val label: String, val rgb: Int) {
    TOP("Top", 0x2979FF),
    RIGHT("Right", 0xFFC400),
    BOTTOM("Bottom", 0xD500F9),
    LEFT("Left", 0x00E676),
}

/** Corner where the strip begins, in screen order. */
enum class Corner(val label: String) { TOP_LEFT("top-left"), TOP_RIGHT("top-right"), BOTTOM_RIGHT("bottom-right"), BOTTOM_LEFT("bottom-left") }

/** LEDs [start, start+count) on the physical strip that belong to one TV side. */
data class Segment(
    val side: Side,
    val start: Int,
    val count: Int,
    /** True when the strip runs against the clockwise direction on this edge. */
    val reversed: Boolean,
) {
    val end: Int get() = start + count - 1

    /** Physical LED indices in strip order. */
    fun indices(total: Int): List<Int> =
        (0 until count).map { start + it }.filter { it in 0 until total }
}

/**
 * The strip is one continuous line around the TV. Per position along the strip we store how many
 * LEDs are hidden before the side (gap) and how many are on it (count); starts are derived.
 */
data class StripConfig(
    val totalLeds: Int = 300,
    val startCorner: Corner = Corner.BOTTOM_LEFT,
    val clockwise: Boolean = true,
    val gaps: List<Int> = listOf(0, 0, 0, 0),
    val counts: List<Int> = listOf(0, 0, 0, 0),
    val configured: Boolean = false,
) {
    /** Sides in the order the strip visits them. */
    val order: List<Side> get() = sideOrder(startCorner, clockwise)

    val segments: List<Segment>
        get() {
            var cursor = 0
            return order.mapIndexed { p, side ->
                cursor += gaps[p]
                val seg = Segment(side, cursor, counts[p], reversed = !clockwise)
                cursor += counts[p]
                seg
            }
        }

    fun segment(pos: Int): Segment = segments[pos]

    fun segmentFor(side: Side): Segment = segments.first { it.side == side }

    /** LEDs still free after everything before [pos] (gap + counts), i.e. the room for this side. */
    fun roomAt(pos: Int): Int {
        val before = (0..pos).sumOf { gaps[it] } + (0 until pos).sumOf { counts[it] }
        return (totalLeds - before).coerceAtLeast(0)
    }

    fun withCount(pos: Int, count: Int): StripConfig =
        copy(counts = counts.toMutableList().also { it[pos] = count.coerceAtLeast(0) }).normalized()

    fun withGap(pos: Int, gap: Int): StripConfig =
        copy(gaps = gaps.toMutableList().also { it[pos] = gap.coerceAtLeast(0) }).normalized()

    fun withLayout(corner: Corner, clockwise: Boolean) =
        copy(startCorner = corner, clockwise = clockwise)

    /** Clamp so that everything fits on the strip; later sides give way first. */
    fun normalized(): StripConfig {
        var used = 0
        val g = gaps.toMutableList()
        val c = counts.toMutableList()
        for (p in 0 until 4) {
            g[p] = g[p].coerceIn(0, (totalLeds - used).coerceAtLeast(0))
            used += g[p]
            c[p] = c[p].coerceIn(0, (totalLeds - used).coerceAtLeast(0))
            used += c[p]
        }
        return copy(gaps = g, counts = c)
    }

    fun withTotal(total: Int): StripConfig = copy(totalLeds = total).normalized()

    fun toJson(): String = JSONObject().apply {
        put("totalLeds", totalLeds)
        put("startCorner", startCorner.name)
        put("clockwise", clockwise)
        put("gaps", JSONArray(gaps))
        put("counts", JSONArray(counts))
        put("configured", configured)
    }.toString()

    companion object {
        /** Sides in strip order for a start corner and direction. */
        fun sideOrder(corner: Corner, clockwise: Boolean): List<Side> {
            val cw = listOf(Side.TOP, Side.RIGHT, Side.BOTTOM, Side.LEFT)
            val ccw = listOf(Side.TOP, Side.LEFT, Side.BOTTOM, Side.RIGHT)
            val c = corner.ordinal
            return if (clockwise) List(4) { cw[(c + it) % 4] }
            else List(4) { ccw[((1 - c) + it).mod(4)] }
        }

        fun fromJson(json: String?): StripConfig {
            if (json.isNullOrBlank()) return StripConfig()
            return runCatching {
                val o = JSONObject(json)
                fun ints(name: String) = o.getJSONArray(name).let { a -> List(4) { a.getInt(it) } }
                StripConfig(
                    totalLeds = o.getInt("totalLeds"),
                    startCorner = Corner.valueOf(o.getString("startCorner")),
                    clockwise = o.getBoolean("clockwise"),
                    gaps = ints("gaps"),
                    counts = ints("counts"),
                    configured = o.getBoolean("configured"),
                ).normalized()
            }.getOrDefault(StripConfig())
        }
    }
}
