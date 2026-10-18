package dev.k3be.restv.data

/**
 * Turns provider strings ("|UK| Sports ᶠᴴᴰ", "AR: beIN 1 4K") into clean names, groups and quality.
 * The UI never shows raw provider strings.
 */
object Naming {

    data class Split(val code: String, val name: String)

    // "UK | Sports", "|FR| Cinéma", "[AR] News", "DE: Sport", "US - News"
    private val prefix = Regex("""^\s*[|\[(]?\s*([A-Za-z]{2,3})\s*[|\]):\-–]+\s*(.+)$""")

    private val superscripts = mapOf(
        'ᴬ' to 'A', 'ᴮ' to 'B', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᴳ' to 'G', 'ᴴ' to 'H', 'ᴵ' to 'I', 'ᴶ' to 'J',
        'ᴷ' to 'K', 'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ᵀ' to 'T',
        'ᵁ' to 'U', 'ⱽ' to 'V', 'ᵂ' to 'W', 'ᶠ' to 'F', 'ˢ' to 'S', 'ᶜ' to 'C', 'ᵈ' to 'D', 'ʰ' to 'H',
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7',
        '⁸' to '8', '⁹' to '9',
    )

    private val q4k = Regex("""(?i)(?<![A-Z0-9])(4K|UHD|2160P?|3840P?)(?![A-Z0-9])""")
    private val qFhd = Regex("""(?i)(?<![A-Z0-9])(FHD|1080P?)(?![A-Z0-9])""")
    private val qHd = Regex("""(?i)(?<![A-Z0-9])(HD|720P?)(?![A-Z0-9])""")
    private val qSd = Regex("""(?i)(?<![A-Z0-9])(SD|576P?|480P?)(?![A-Z0-9])""")
    private val qualityTokens = Regex("""(?i)(?<![A-Z0-9])(4K|UHD|2160P?|3840P?|FHD|1080P?|HD|720P?|SD|576P?|480P?|HEVC|H\.?265|50FPS|60FPS)(?![A-Z0-9])""")
    private val junk = Regex("""[|\[\](){}★☆●•◉✪➤]+""")
    private val spaces = Regex("""\s{2,}""")

    private val groupLabels = mapOf(
        "UK" to "United Kingdom", "GB" to "United Kingdom", "US" to "United States", "AR" to "Arabic",
        "FR" to "France", "DE" to "Germany", "IT" to "Italy", "ES" to "Spain", "PT" to "Portugal",
        "NL" to "Netherlands", "BE" to "Belgium", "TR" to "Turkey", "IN" to "India", "PK" to "Pakistan",
        "IR" to "Iran", "IQ" to "Iraq", "KU" to "Kurdish", "KRD" to "Kurdish", "SA" to "Saudi Arabia",
        "AE" to "UAE", "EG" to "Egypt", "MA" to "Morocco", "DZ" to "Algeria", "TN" to "Tunisia",
        "CA" to "Canada", "AU" to "Australia", "BR" to "Brazil", "MX" to "Mexico", "PL" to "Poland",
        "RU" to "Russia", "SE" to "Sweden", "NO" to "Norway", "DK" to "Denmark", "FI" to "Finland",
        "GR" to "Greece", "AL" to "Albania", "EX" to "Ex-Yu", "AF" to "Africa", "LAT" to "Latino",
        "EN" to "English", "VIP" to "VIP", "PPV" to "Pay per view",
    )

    fun normalize(raw: String): String =
        buildString(raw.length) { raw.forEach { append(superscripts[it] ?: it) } }

    fun split(raw: String): Split {
        val n = normalize(raw).trim()
        val m = prefix.find(n)
        if (m != null) {
            val code = m.groupValues[1].uppercase()
            val rest = m.groupValues[2].trim()
            if (rest.isNotEmpty()) return Split(code, rest)
        }
        return Split("", n)
    }

    fun groupLabel(code: String): String = when {
        code.isEmpty() -> "Other"
        else -> groupLabels[code] ?: code
    }

    fun quality(raw: String): Int {
        val n = normalize(raw)
        return when {
            q4k.containsMatchIn(n) -> 3
            qFhd.containsMatchIn(n) -> 2
            qHd.containsMatchIn(n) -> 1
            qSd.containsMatchIn(n) -> 0
            else -> 0
        }
    }

    fun cleanChannelName(raw: String): String {
        val name = split(raw).name
        val cleaned = qualityTokens.replace(name, " ").let { junk.replace(it, " ") }
            .let { spaces.replace(it, " ") }.trim().trim('-', ':', '.', ' ')
        return cleaned.ifEmpty { name }
    }

    fun variantKey(raw: String): String =
        cleanChannelName(raw).lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")

    /**
     * Identity of a channel *including* its source prefix ("NM:", "SS |"), minus quality tags.
     * Only channels with the same source key are treated as quality variants of each other.
     */
    fun sourceKey(raw: String): String =
        qualityTokens.replace(normalize(raw), " ").lowercase().filter { it.isLetterOrDigit() }

    /** The provider's source/region tag shown on cards, e.g. "NM" for "NM: beIN 1". */
    fun sourceTag(raw: String): String? = split(raw).code.takeIf { it.isNotEmpty() }

    fun qualityLabel(q: Int): String? = when (q) {
        3 -> "4K"
        2 -> "FHD"
        1 -> "HD"
        else -> null
    }
}
