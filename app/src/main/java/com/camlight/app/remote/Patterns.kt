package com.camlight.app.remote

/** A full-screen test pattern the TV page can show. [rgb] is exact sRGB; [style] "edges" adds border markers. */
data class Pattern(val id: String, val label: String, val rgb: Int, val style: String = "solid") {
    companion object {
        const val NONE = "none"

        val ALL = listOf(
            Pattern("black", "Black", 0x000000),
            Pattern("white", "White", 0xFFFFFF),
            Pattern("gray25", "Gray 25%", 0x404040),
            Pattern("gray50", "Gray 50%", 0x808080),
            Pattern("gray75", "Gray 75%", 0xBFBFBF),
            Pattern("red", "Red", 0xFF0000),
            Pattern("green", "Green", 0x00FF00),
            Pattern("blue", "Blue", 0x0000FF),
            Pattern("cyan", "Cyan", 0x00FFFF),
            Pattern("magenta", "Magenta", 0xFF00FF),
            Pattern("yellow", "Yellow", 0xFFFF00),
            Pattern("edges", "Edge markers", 0xFFFFFF, style = "edges"),
        )

        fun byId(id: String): Pattern? = ALL.firstOrNull { it.id == id }
    }
}
