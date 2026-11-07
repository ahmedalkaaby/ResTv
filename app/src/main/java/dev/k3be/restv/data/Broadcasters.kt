package dev.k3be.restv.data

/**
 * Which channels a broadcaster really means, per country.
 *
 * LiveSoccerTV often names a network or an app ("DAZN", "Movistar+", "Sky Go", "Canal+") rather than
 * a channel. Each network here lists only its sports channels, and for the big competitions the ones
 * that carry them (LaLiga → "M+ LaLiga" / "DAZN LaLiga", Champions League → "M+ Liga de Campeones").
 * Nothing outside these patterns is ever picked for a network name: no Movistar films, no Sky Family.
 *
 * Patterns are regular expressions over the channel's core name (lowercase, no accents, region,
 * quality or "TV" words), matched in full.
 */
object Broadcasters {

    class Brand(
        /** Names LiveSoccerTV uses for the network or its app, in core form. */
        val aliases: List<String>,
        /** The network's football channels (used when nothing more specific is known). */
        val any: List<String>,
        /** Per competition key, the channels that normally carry it, best first. */
        val byComp: Map<String, List<String>> = emptyMap(),
    ) {
        val aliasCores: Set<String> by lazy { aliases.map(::core).toSet() }
        val anyRx = any.map { Regex(it) }
        val compRx = byComp.mapValues { (_, v) -> v.map { Regex(it) } }
    }

    class Country(val key: String, val label: String, val names: List<String>, val codes: Set<String>, val brands: List<Brand>)

    // ---- Networks used in several countries.

    private val amazon = Brand(
        listOf("amazon prime video", "prime video", "amazon prime", "amazon", "prime video sport", "amazon prime video sport"),
        listOf("(amazon )?prime (video )?(sports?|football|event|ppv|epl|live)( \\d+)?", "amazon (sports?|ppv|event|live)( \\d+)?"),
    )
    private fun dazn(vararg extra: String) = Brand(
        listOf("dazn", "dazn app") + extra,
        listOf("dazn( [1-6])?", "dazn laliga( \\d+)?", "dazn (zona|bundesliga|ligue 1|serie a)( \\d+)?", "zona dazn"),
        mapOf("laliga" to listOf("dazn laliga( \\d+)?"), "seriea" to listOf("zona dazn", "dazn( [1-2])?")),
    )
    private val skyUk = Brand(
        listOf("sky sports", "sky go", "now", "now tv", "sky sports app", "sky", "sky sports plus", "sky sports football app"),
        listOf("sky sports (main event|premier league|football|mix|plus|ultra|ultra hdr)"),
        mapOf(
            "pl" to listOf("sky sports (main event|premier league|ultra|ultra hdr)"),
            "eng" to listOf("sky sports (football|main event)"),
        ),
    )
    private val tnt = Brand(
        listOf("tnt sports", "discovery", "discovery plus", "tnt sports app", "hbo max", "bt sport"),
        listOf("tnt sports? [1-4]", "tnt sports? ultimate", "bt sport [1-4]", "bt sport ultimate"),
    )
    private val premierSports = Brand(
        listOf("premier sports", "premier sports player", "premier player", "viaplay"),
        listOf("premier sports( [1-3])?"),
    )
    private val skyDe = Brand(
        listOf("sky", "sky go", "sky sport", "wow", "sky sport app", "sky ticket", "sky x"),
        listOf("sky sport (top event|mix|premier league|bundesliga( \\d+)?|\\d+|austria( \\d+)?)"),
        mapOf(
            "bundesliga" to listOf("sky sport (bundesliga( \\d+)?|top event)"),
            "pl" to listOf("sky sport (premier league|top event|mix)"),
        ),
    )

    val countries: List<Country> = listOf(
        Country("uk", "UK", listOf("great britain", "united kingdom", "england", "uk", "scotland", "wales", "northern ireland"),
            setOf("UK", "GB", "EN", "ENG"), listOf(skyUk, tnt, premierSports, amazon)),
        Country("ireland", "Ireland", listOf("ireland", "republic of ireland"), setOf("IE", "IRL", "UK", "GB"), listOf(
            skyUk, tnt, premierSports, amazon,
            Brand(listOf("virgin media", "virgin media play"), listOf("virgin media (one|two|sport|1|2)")),
        )),
        Country("us", "US", listOf("usa", "united states", "united states of america", "us"), setOf("US", "USA"), listOf(
            Brand(listOf("paramount", "paramount plus", "cbs", "cbs sports", "cbs sports app", "cbs sports network", "cbs sports golazo"),
                listOf("cbs sports golazo( network)?", "golazo( network)?", "cbs sports network", "cbs sports hq", "cbs", "paramount (plus )?sports?( \\d+)?")),
            Brand(listOf("espn", "espn plus", "espn app", "espn deportes", "disney plus", "espn unlimited"),
                listOf("espn( [2-3])?", "espnu", "espn deportes", "espn plus( \\d+)?")),
            Brand(listOf("peacock", "peacock premium", "nbc", "usa network", "nbc sports", "telemundo", "universo", "nbc sports app"),
                listOf("usa network", "nbc", "nbc sports( network)?", "telemundo", "universo", "peacock( premium)?( event)?( \\d+)?")),
            Brand(listOf("fox", "fox sports", "fox one", "fox sports app", "fs1", "fs2", "fox deportes", "tubi"),
                listOf("fs[12]", "fox sports [12]", "fox deportes", "fox soccer plus", "fox")),
            Brand(listOf("tudn", "tudn usa", "univision", "unimas", "vix", "vix premium", "tudn app"),
                listOf("tudn( usa)?", "univision", "unimas", "galavision")),
            Brand(listOf("bein sports", "bein sports usa", "bein sports xtra", "bein sports en espanol", "fanatiz"),
                listOf("bein sports( usa)?( [1-3])?", "bein sports xtra( en espanol)?", "bein sports en espanol")),
            Brand(listOf("fubo", "fubotv", "fubo sports"), listOf("fubo sports( network)?")),
        )),
        Country("canada", "Canada", listOf("canada"), setOf("CA", "CAN"), listOf(
            dazn("dazn canada"),
            Brand(listOf("tsn", "tsn plus", "tsn app", "ctv", "tsn direct"), listOf("tsn ?[1-5]", "tsn", "ctv")),
            Brand(listOf("sportsnet", "sportsnet plus", "sportsnet now", "sn now", "sn"),
                listOf("sportsnet( (one|east|west|ontario|pacific|360|world|\\d+))?", "sn ?(1|one|360|east|west|ontario|pacific|world)")),
            Brand(listOf("tva sports", "tva sports direct"), listOf("tva sports( [1-3])?")),
            Brand(listOf("rds", "rds direct"), listOf("rds( 2| info)?")),
            Brand(listOf("fubo", "fubotv", "fubo canada", "fubo sports"), listOf("fubo sports( network)?", "fubo (tv )?canada( \\d+)?")),
            Brand(listOf("onesoccer", "one soccer"), listOf("one ?soccer")),
        )),
        Country("spain", "Spain", listOf("spain"), setOf("ES", "SP", "ESP"), listOf(
            Brand(
                listOf("movistar", "movistar plus", "m", "movistar m", "m plus", "movistar plus app", "movistar laliga app"),
                listOf("(m|movistar) (laliga|liga de campeones|champions|deportes|vamos|futbol|ellas|europa league)( \\d+)?"),
                mapOf(
                    "laliga" to listOf("(m|movistar) laliga( \\d+)?"),
                    "ucl" to listOf("(m|movistar) (liga de campeones|champions)( \\d+)?"),
                    "uel" to listOf("(m|movistar) (liga de campeones|europa league|deportes)( \\d+)?"),
                    "uecl" to listOf("(m|movistar) (liga de campeones|europa league|deportes)( \\d+)?"),
                    "es" to listOf("(m|movistar) (laliga|vamos|deportes)( \\d+)?"),
                ),
            ),
            dazn("dazn espana"),
            Brand(listOf("laliga plus", "laliga tv", "laliga hypermotion", "laliga tv hypermotion"),
                listOf("((m|movistar) )?laliga hypermotion( \\d+)?")),
            amazon,
        )),
        Country("france", "France", listOf("france"), setOf("FR", "FRA"), listOf(
            Brand(listOf("canal", "canal plus", "mycanal", "canal app", "canal live", "canal sport"),
                listOf("canal (foot|sport( 360)?|\\d+|premier league)", "canal"),
                mapOf("ucl" to listOf("canal (foot|sport( 360)?|\\d+)", "canal"), "pl" to listOf("canal (foot|premier league|sport( 360)?)"))),
            Brand(listOf("ligue 1", "ligue 1 plus", "ligue1", "ligue1 plus"), listOf("ligue ?1( plus)?( \\d+)?")),
            Brand(listOf("bein sports", "bein sport", "bein sports france"), listOf("bein sports? [1-3]", "bein sports? max \\d+")),
            Brand(listOf("rmc sport", "rmc sport app"), listOf("rmc sport [1-4]", "rmc sport \\d+")),
            dazn("dazn france"),
            amazon,
        )),
        Country("germany", "Germany", listOf("germany"), setOf("DE", "GER"), listOf(skyDe, dazn("dazn deutschland", "dazn germany"), amazon)),
        Country("austria", "Austria", listOf("austria"), setOf("AT", "DE", "GER"), listOf(skyDe, dazn(), amazon)),
        Country("switzerland", "Switzerland", listOf("switzerland"), setOf("CH", "DE", "GER"), listOf(
            Brand(listOf("blue", "blue sport", "blue tv", "blue sport app"), listOf("blue sport( \\d+| \\d+)?")),
            skyDe,
        )),
        Country("italy", "Italy", listOf("italy"), setOf("IT", "ITA"), listOf(
            dazn("dazn italia"),
            Brand(listOf("sky", "sky go", "now", "now tv", "sky sport", "sky sport app"),
                listOf("sky sport (uno|calcio|football|max|arena|24|25[1-9]|\\d+)"),
                mapOf("ucl" to listOf("sky sport (uno|calcio|football|25[1-9]|\\d+)"), "seriea" to listOf("sky sport (calcio|uno|25[1-9])"))),
            amazon,
        )),
        Country("netherlands", "Netherlands", listOf("netherlands", "holland"), setOf("NL"), listOf(
            Brand(listOf("espn", "espn watch", "espn nl"), listOf("espn( [1-4])?")),
            Brand(listOf("ziggo sport", "ziggo", "ziggo go"), listOf("ziggo sport( [1-6]| select| totaal)?")),
            dazn(),
        )),
        Country("belgium", "Belgium", listOf("belgium"), setOf("BE", "NL", "FR"), listOf(
            Brand(listOf("play sports", "play sports app", "telenet", "play sports pro league"), listOf("play sports( \\d+)?")),
            Brand(listOf("eleven", "eleven sports", "eleven pro league"), listOf("eleven( sports)?( pro league)?( [1-6])?")),
            Brand(listOf("pickx", "proximus pickx", "proximus"), listOf("pickx sports?( \\d+)?", "proximus (11|sports?)( \\d+)?")),
            dazn(),
        )),
        Country("portugal", "Portugal", listOf("portugal"), setOf("PT", "POR"), listOf(
            Brand(listOf("sport tv", "sporttv", "sport tv app"), listOf("sport( ?tv)? ?[1-7]", "sport( ?tv)? plus")),
            dazn("eleven", "eleven sports"),
            Brand(listOf("benfica tv", "btv"), listOf("benfica( tv)?( \\d+)?", "btv( \\d+)?")),
        )),
        Country("poland", "Poland", listOf("poland"), setOf("PL", "POL"), listOf(
            Brand(listOf("canal", "canal plus", "canal online", "canal sport"), listOf("canal sport( [1-5]| extra \\d+)?", "canal premium")),
            Brand(listOf("polsat sport", "polsat box go", "polsat", "polsat sport premium"), listOf("polsat sport( [1-3]| premium [1-2]| extra( \\d+)?)?")),
            Brand(listOf("eleven", "eleven sports"), listOf("eleven sports( [1-4])?")),
            Brand(listOf("viaplay"), listOf("viaplay( sport)?( \\d+)?")),
        )),
        Country("sweden", "Sweden", listOf("sweden"), setOf("SE", "SW", "SWE"), listOf(
            Brand(listOf("v sport", "viaplay", "viafree", "v sport fotboll"), listOf("v sport( fotboll| premium| extra| \\d+| [1-4])?", "viaplay( sport)?( \\d+)?")),
            Brand(listOf("tv4", "tv4 play", "c more", "tv4 sport", "tv4 fotboll"), listOf("tv4 (sport|fotboll|sportkanalen|sport \\d+)", "c more (fotboll|sport|\\d+)", "tv4")),
        )),
        Country("norway", "Norway", listOf("norway"), setOf("NO", "NOR"), listOf(
            Brand(listOf("v sport", "viaplay", "v sport premier league"), listOf("v sport( fotball| premier league| [1-4]| \\d+)?", "viaplay( sport)?( \\d+)?")),
            Brand(listOf("tv 2", "tv2", "tv 2 play", "tv 2 sport"), listOf("(tv ?)?2 sport( \\d+| premium( \\d+)?)?")),
        )),
        Country("denmark", "Denmark", listOf("denmark"), setOf("DK", "DEN"), listOf(
            Brand(listOf("viaplay", "tv3 sport", "tv3", "tv3 max", "v sport"), listOf("tv3 sport( \\d+)?", "tv3 max", "tv3", "viaplay( sport)?( \\d+)?", "v sport( \\d+)?")),
            Brand(listOf("tv 2", "tv2", "tv 2 play", "tv 2 sport"), listOf("(tv ?)?2 sport( x)?")),
        )),
        Country("finland", "Finland", listOf("finland"), setOf("FI", "FIN"), listOf(
            Brand(listOf("v sport", "viaplay", "v sport jalkapallo"), listOf("v sport( jalkapallo| premium| [1-4]| \\d+)?", "viaplay( sport)?( \\d+)?")),
            Brand(listOf("mtv", "mtv katsomo", "mtv3", "mtv sport"), listOf("mtv ?sport( \\d+)?", "mtv3")),
            Brand(listOf("ruutu", "ruutu plus"), listOf("ruutu( sport)?( \\d+)?")),
        )),
        Country("greece", "Greece", listOf("greece"), setOf("GR", "GRE"), listOf(
            Brand(listOf("cosmote sport", "cosmote tv"), listOf("cosmote sport( \\d+)?")),
            Brand(listOf("nova", "nova sports", "nova go"), listOf("nova sports?( \\d+| premier league| start| prime)?")),
        )),
        Country("turkey", "Türkiye", listOf("turkey", "turkiye"), setOf("TR", "TUR"), listOf(
            Brand(listOf("bein sports", "bein sports turkiye", "bein sports haber"), listOf("bein sports? ?[1-5]", "bein sports? max ?\\d+", "bein sports haber")),
            Brand(listOf("tabii", "tabii spor", "trt spor", "trt"), listOf("tabii spor( \\d+)?", "trt spor( yildiz)?")),
            Brand(listOf("s sport", "s sport plus"), listOf("s sport ?(\\d+|plus)?")),
            Brand(listOf("exxen", "exxen spor", "exxenspor"), listOf("exxen ?spor( \\d+)?")),
            Brand(listOf("tivibu", "tivibu spor"), listOf("tivibu spor( \\d+)?")),
        )),
        Country("balkans", "Balkans", listOf("croatia", "serbia", "bosnia and herzegovina", "bosnia", "slovenia", "montenegro", "north macedonia", "macedonia", "kosovo"),
            setOf("HR", "RS", "BIH", "BA", "SI", "SLO", "MK", "ME", "EXYU", "YU", "SRB", "CRO"), listOf(
            Brand(listOf("arena sport", "arena sport premium", "arena premium", "max tv"), listOf("arena (sport|premium)( premium)?( \\d+)?( (hr|rs|ba|bih|slo|mk))?")),
            Brand(listOf("sport klub", "sportklub", "sk"), listOf("sport ?klub( \\d+)?( (hr|rs|slo))?", "sk ?\\d+")),
        )),
        Country("romania", "Romania", listOf("romania"), setOf("RO", "ROM"), listOf(
            Brand(listOf("digi sport", "digi online", "digi"), listOf("digi sport ?[1-4]")),
            Brand(listOf("prima sport", "prima play"), listOf("prima sport ?[1-5]")),
            Brand(listOf("orange sport", "orange tv go"), listOf("orange sport ?[1-4]")),
        )),
        Country("hungary", "Hungary", listOf("hungary"), setOf("HU", "HUN"), listOf(
            Brand(listOf("arena4", "network4", "arena 4"), listOf("arena ?4( plus)?")),
            Brand(listOf("spiler", "spiler tv"), listOf("spiler ?[1-2]( tv)?")),
            Brand(listOf("m4 sport"), listOf("m4 sport( plus)?")),
        )),
        Country("czechia", "Czechia & Slovakia", listOf("czech republic", "czechia", "slovakia"), setOf("CZ", "SK", "CZE", "SVK"), listOf(
            Brand(listOf("nova sport", "voyo", "oneplay"), listOf("nova sport ?[1-6]")),
            Brand(listOf("o2 tv", "o2 sport", "o2 tv sport"), listOf("o2( tv)? (sport|fotbal)( \\d+)?")),
            Brand(listOf("ct sport", "ct"), listOf("ct sport( plus)?")),
        )),
        Country("bulgaria", "Bulgaria", listOf("bulgaria"), setOf("BG", "BUL"), listOf(
            Brand(listOf("diema sport", "nova play"), listOf("diema sport ?[1-3]")),
            Brand(listOf("max sport", "a1", "a1 xplore"), listOf("max sport ?[1-4]")),
            Brand(listOf("ring", "ring bg"), listOf("ring( bg)?")),
        )),
        Country("albania", "Albania", listOf("albania"), setOf("AL", "ALB"), listOf(
            Brand(listOf("supersport", "super sport", "tring sport"), listOf("super ?sport ?[1-7]", "tring sport ?\\d+")),
        )),
    )

    private val byName: Map<String, Country> = countries.flatMap { c -> c.names.map { it to c } }.toMap()

    fun country(liveSoccerTvName: String): Country? =
        byName[fold(liveSoccerTvName).trim().lowercase()]

    fun brand(country: Country, broadcasterCore: String): Brand? =
        country.brands.firstOrNull { b -> broadcasterCore in b.aliasCores }

    /**
     * Competition keys used for channel picks and themes: pl, eng, laliga, es, ucl, uel, uecl,
     * bundesliga, seriea, ligue1, saudi, iraq, turkey, intl, other.
     */
    fun competition(league: String?, competition: String?): String {
        val l = league.orEmpty().lowercase()
        val c = fold(competition.orEmpty()).lowercase()
        return when {
            "europa-conference" in l || "conference league" in c -> "uecl"
            ("europa-league" in l || "europa league" in c) -> "uel"
            ("champions-league" in l || "champions league" in c) && listOf("afc", "caf", "concacaf", "asia", "africa").none { it in "$l $c" } -> "ucl"
            ("england" in l && l.endsWith("premier-league")) || c == "premier league" -> "pl"
            l.startsWith("england/") -> "eng"
            ("spain" in l && listOf("primera", "la-liga", "laliga").any { it in l }) || "la liga" in c || "laliga" in c -> "laliga"
            l.startsWith("spain/") -> "es"
            ("germany" in l && l.endsWith("/bundesliga")) || c == "bundesliga" -> "bundesliga"
            ("italy" in l && l.endsWith("serie-a")) || c == "serie a" -> "seriea"
            ("france" in l && l.endsWith("ligue-1")) || c == "ligue 1" -> "ligue1"
            "saudi" in l || "saudi" in c -> "saudi"
            "iraq" in l || "iraq" in c -> "iraq"
            "turkey" in l || "super lig" in c -> "turkey"
            listOf("international", "world-cup", "world cup", "nations-league", "nations league", "euro", "asian-cup", "asian cup",
                "qualif", "friendl", "arab-cup", "arab cup", "gulf-cup", "gulf cup", "copa-america", "copa america", "africa-cup").any { it in "$l $c" } -> "intl"
            else -> "other"
        }
    }

    /**
     * Reduces a channel or broadcaster name to what identifies it: "beIN Sports Arabia 5 HD" and
     * "beIN SPORTS 5 4K" both become "bein sports 5". Region, quality, country and "TV" words go.
     */
    fun core(name: String): String =
        fold(Naming.normalize(name)).lowercase()
            .replace(Regex("""\(.*?\)"""), " ")
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotBlank() && it !in noise }
            .joinToString(" ")

    private val noise = setOf(
        "mena", "arabia", "arabic", "ar", "uk", "hd", "fhd", "uhd", "4k", "sd", "hevc", "tv", "channel", "live",
        "global", "international", "middle", "east", "north", "africa",
        // Country words LiveSoccerTV adds ("DAZN 1 Spain", "beIN Sports France 1"); the country is known separately.
        "spain", "espana", "france", "germany", "deutschland", "italia", "italy", "canada", "portugal", "turkiye",
        "turkey", "nederland", "netherlands", "belgium", "belgique", "suisse", "schweiz", "austria", "osterreich", "poland", "polska",
        "raw", "backup", "vip", "fps", "50fps", "60fps", "h265", "1080p", "720p", "2160p", "1080", "720", "2160",
    )

    fun fold(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
            .replace('ı', 'i').replace('ł', 'l').replace('ø', 'o').replace('đ', 'd')
}
