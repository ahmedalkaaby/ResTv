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

    /** Icons providers put in place of letters: "beIN SP⚽RTS" → "beIN SPORTS". */
    private val letterIcons = mapOf(
        '⚽' to 'O', '⚾' to 'O', '○' to 'O', '◯' to 'O', '⭕' to 'O', '◎' to 'O', '⊙' to 'O',
    )

    fun normalize(raw: String): String {
        val mapped = buildString(raw.length) {
            raw.forEach { append(superscripts[it] ?: letterIcons[it] ?: it) }
        }
        // NFKC folds fullwidth, circled and styled letters ("ＳＰＯＲＴＳ", "ⓢⓟⓞⓡⓣⓢ") to plain ones.
        return java.text.Normalizer.normalize(mapped, java.text.Normalizer.Form.NFKC)
            .replace("\uFE0F", "") // emoji variation selector left behind by ⚽️
    }

    /** Provider divider rows such as "##### SPORTS #####" — not real channels. */
    fun isSeparator(raw: String): Boolean =
        raw.contains("###") || raw.contains("===") || raw.contains("***") ||
            raw.count { it.isLetterOrDigit() } < 2

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

/** Movie and series names: "EN - The Batman (2022) [4K]" → "The Batman", 2022. */
object VodNaming {
    private val yearParen = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val yearTail = Regex("""[\s\-–|:]+((?:19|20)\d{2})\s*$""")
    // Android's regex engine (ICU) needs closing ] and } escaped too.
    private val brackets = Regex("""\[[^\]]*\]|\{[^\}]*\}""")
    private val tags = Regex("""(?i)(?<![A-Z0-9])(4K|UHD|2160P?|FHD|1080P?|HD|720P?|SD|HEVC|H\.?265|x265|HDR10?\+?|DV|DOLBY VISION|MULTI[- ]?SUBS?|MULTI[- ]?AUDIO|SUB(?:BED|S)?|DUBBED|VOSTFR)(?![A-Z0-9])""")
    private val leadingTag = Regex("""^\s*(?:4K|UHD|FHD|HD|VIP|NF|AMZ|DSNP|ATVP|HMAX)\s*[|:\-–]+\s*""", RegexOption.IGNORE_CASE)

    data class Parsed(val title: String, val year: Int?)

    fun parse(raw: String, yearField: String? = null): Parsed {
        var n = Naming.split(raw).name
        n = leadingTag.replace(n, "")
        n = Naming.split(n).name // double prefixes: "4K | EN - Title"
        var year = yearField?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        yearParen.find(n)?.let { m -> if (year == null) year = m.groupValues[1].toInt(); n = n.removeRange(m.range) }
        n = brackets.replace(n, " ")
        n = tags.replace(n, " ")
        n = n.replace(Regex("""\s{2,}"""), " ").trim().trim('-', '|', ':', '.', ' ', '–')
        yearTail.find(n)?.let { m ->
            // Keep titles that are a year ("1917", "2012").
            if (m.range.first > 0) { if (year == null) year = m.groupValues[1].toInt(); n = n.removeRange(m.range).trim() }
        }
        return Parsed(n.ifEmpty { Naming.split(raw).name.trim() }, year)
    }

    /** Key for matching titles across sources: no accents, articles, punctuation or spaces. */
    fun key(title: String): String {
        val folded = java.text.Normalizer.normalize(Naming.normalize(title), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace("&", " and ")
        return folded.split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotEmpty() }
            .let { if (it.size > 1 && it.first() in setOf("the", "a", "an")) it.drop(1) else it }
            .joinToString("")
    }

    /** Streaming platform of a category name, in the order they're shown. */
    val platforms = listOf("Apple TV+", "Netflix", "Disney+", "HBO", "OSN+")

    fun platformOf(categoryName: String): String? {
        val n = Naming.normalize(categoryName).lowercase()
        return when {
            "apple" in n -> "Apple TV+"
            "netflix" in n -> "Netflix"
            "disney" in n -> "Disney+"
            "hbo" in n -> "HBO"
            Regex("""(?<![a-z])osn""").containsMatchIn(n) -> "OSN+"
            else -> null
        }
    }
}
