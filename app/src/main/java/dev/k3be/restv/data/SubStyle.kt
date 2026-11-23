package dev.k3be.restv.data

/**
 * How subtitles look, for both the file's own tracks and SubDL lines. Stored as one short string.
 * [size] 0..4 (Smallest..Largest), [color] an index into [COLORS], [opacity] into [OPACITY].
 */
data class SubStyle(
    val size: Int = 2,
    val bold: Boolean = true,
    val color: Int = 0,
    val opacity: Int = 0,
    val background: Boolean = false,
    val outline: Boolean = true,
    val shadow: Boolean = true,
) {
    val sizeLabel: String get() = SIZES[size.coerceIn(0, 4)]
    val colorLabel: String get() = COLORS[color.coerceIn(0, COLORS.lastIndex)].first
    val opacityLabel: String get() = "${OPACITY[opacity.coerceIn(0, OPACITY.lastIndex)]}%"

    /** Text colour as ARGB with the chosen opacity. */
    val argb: Int get() {
        val rgb = COLORS[color.coerceIn(0, COLORS.lastIndex)].second
        val a = (OPACITY[opacity.coerceIn(0, OPACITY.lastIndex)] * 255 / 100)
        return (a shl 24) or (rgb and 0xFFFFFF)
    }
    /** Share of the picture height for the file's tracks, and sp for lines the app draws. */
    val fraction: Float get() = listOf(0.040f, 0.048f, 0.058f, 0.068f, 0.080f)[size.coerceIn(0, 4)]
    val sp: Int get() = listOf(18, 22, 26, 30, 35)[size.coerceIn(0, 4)]

    fun encode(): String = listOf(size, if (bold) 1 else 0, color, opacity, if (background) 1 else 0, if (outline) 1 else 0, if (shadow) 1 else 0).joinToString(",")

    companion object {
        val SIZES = listOf("Smallest", "Small", "Medium", "Large", "Largest")
        val COLORS = listOf("White" to 0xFFFFFF, "Yellow" to 0xFFE14D, "Cyan" to 0x64D2FF, "Green" to 0x7CE38B)
        val OPACITY = listOf(100, 85, 70, 50)

        fun decode(s: String?): SubStyle? {
            val p = s?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 7 } ?: return null
            return SubStyle(p[0].coerceIn(0, 4), p[1] == 1, p[2].coerceIn(0, COLORS.lastIndex), p[3].coerceIn(0, OPACITY.lastIndex), p[4] == 1, p[5] == 1, p[6] == 1)
        }
    }
}
