package dev.k3be.restv.data

/** Team names as LiveSoccerTV writes them, reduced so spellings of the same team compare equal. */
object Teams {

    data class Pick(val name: String, val national: Boolean)

    /** Ready-made choices in Settings; any other name can be typed. */
    val clubs = listOf(
        "Real Madrid", "Barcelona", "Atletico Madrid", "Manchester City", "Liverpool", "Arsenal", "Manchester United",
        "Chelsea", "Tottenham Hotspur", "Newcastle United", "Bayern Munich", "Borussia Dortmund", "Bayer Leverkusen",
        "Paris Saint-Germain", "Marseille", "Juventus", "Inter", "AC Milan", "Napoli", "Roma",
        "Al Hilal", "Al Nassr", "Al Ittihad", "Al Ahli", "Galatasaray", "Fenerbahce", "Besiktas",
        "Al Shorta", "Al Zawraa", "Al Quwa Al Jawiya", "Al Talaba", "Al Ahly", "Zamalek",
    )
    val nations = listOf(
        "Iraq", "Saudi Arabia", "Jordan", "Morocco", "Egypt", "Algeria", "Qatar", "Spain", "England", "France",
        "Germany", "Portugal", "Brazil", "Argentina", "Italy", "Netherlands",
    )

    fun isNational(name: String) = key(name) in nations.map(::key).toSet()

    private val filler = setOf("fc", "cf", "afc", "sc", "ac", "club", "cd", "sfc", "sk", "jk", "de", "futbol", "calcio", "ssc", "as", "the", "cp", "sl")

    private val aliases = mapOf(
        "man city" to "manchester city",
        "man utd" to "manchester united", "man united" to "manchester united", "manchester utd" to "manchester united",
        "spurs" to "tottenham", "tottenham hotspur" to "tottenham",
        "newcastle" to "newcastle united",
        "inter milan" to "inter", "internazionale" to "inter",
        "bayern munich" to "bayern", "bayern munchen" to "bayern",
        "borussia dortmund" to "dortmund", "bvb" to "dortmund",
        "bayer leverkusen" to "leverkusen", "bayer 04 leverkusen" to "leverkusen",
        "paris saint germain" to "psg", "paris sg" to "psg", "paris st germain" to "psg",
        "atletico" to "atletico madrid", "atl madrid" to "atletico madrid",
        "barca" to "barcelona",
        "olympique marseille" to "marseille", "olympique de marseille" to "marseille", "om" to "marseille",
        "juve" to "juventus",
        "al hilal saudi" to "al hilal", "al hilal riyadh" to "al hilal",
        "al ahli saudi" to "al ahli", "al ahli jeddah" to "al ahli",
        "al ittihad jeddah" to "al ittihad", "ittihad" to "al ittihad",
        "al nassr riyadh" to "al nassr",
        "al shurta" to "al shorta", "shorta" to "al shorta",
        "al zawra" to "al zawraa", "zawraa" to "al zawraa",
        "al quwa aljawiya" to "al quwa al jawiya", "air force" to "al quwa al jawiya", "al quwa al jawia" to "al quwa al jawiya",
        "al talabah" to "al talaba",
        "al ahly cairo" to "al ahly", "al ahly sc" to "al ahly",
        "zamalek sc" to "zamalek",
        "ksa" to "saudi arabia", "uae" to "united arab emirates", "usa" to "united states",
        "korea republic" to "south korea", "turkiye" to "turkey",
    )

    /** "FC Barcelona", "Barcelona" → "barcelona"; "Paris Saint-Germain", "PSG" → "psg". */
    fun key(name: String): String {
        val words = Broadcasters.fold(name).lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
            .split(' ').filter { it.isNotEmpty() && it !in filler }
        val joined = words.joinToString(" ")
        return aliases[joined] ?: joined
    }
}
