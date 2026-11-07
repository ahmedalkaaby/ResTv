package dev.k3be.restv.data

import dev.k3be.restv.data.db.AppDatabase
import dev.k3be.restv.data.db.ChannelEntity
import dev.k3be.restv.data.xtream.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class Match(
    val id: String,
    val kickoff: Long?,
    val competition: String?,
    val home: String,
    val away: String,
    val broadcasters: List<String>,
    /** LiveSoccerTV match page, for worldwide broadcasters and crests. */
    val url: String? = null,
    /** Competition path, e.g. "england/premier-league". */
    val league: String? = null,
    val homeLogo: String? = null,
    val awayLogo: String? = null,
)

/**
 * Today's matches. Fetched by the TV itself from LiveSoccerTV (home connection, right country),
 * cached per day on the TV; the user's Restv server is a fallback if the direct fetch fails.
 */
class MatchesRepo(
    private val http: OkHttpClient,
    private val json: Json,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val cacheDir: File,
) {
    private val lstv = LiveSoccerTv(http)

    data class Result(val matches: List<Match>, val source: String, val fetchedAt: Long)

    suspend fun fetch(date: String, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val cached = readCache(date)
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < 6 * 3_600_000L) {
            return@withContext cached
        }
        val direct = runCatching { lstv.fetchDay(LocalDate.parse(date)) }
        direct.getOrNull()?.let { list ->
            val r = Result(list, "LiveSoccerTV", System.currentTimeMillis())
            writeCache(date, r)
            return@withContext r
        }
        val server = if (prefs.matchesUrl() != null) runCatching { fromServer(date) } else null
        server?.getOrNull()?.let { return@withContext Result(it, "your server", System.currentTimeMillis()) }
        cached ?: throw IOException(
            "Couldn't load matches: " + (direct.exceptionOrNull()?.message ?: "unknown error") +
                (server?.exceptionOrNull()?.message?.let { " · server: $it" } ?: "")
        )
    }

    private fun cacheFile(date: String) = File(cacheDir, "matches/v2-$date.json")

    /** Whatever was saved for this day, however old: shown at once while a fresh copy loads. */
    suspend fun cached(date: String): Result? = withContext(Dispatchers.IO) { readCache(date) }

    private fun readCache(date: String): Result? = runCatching {
        val f = cacheFile(date)
        if (!f.exists()) return null
        val root = json.parseToJsonElement(f.readText()).jsonObject
        Result(parseMatches(root), root.str("source") ?: "cache", root.str("fetched_at")?.toLongOrNull() ?: f.lastModified())
    }.getOrNull()

    private fun writeCache(date: String, r: Result) {
        val f = cacheFile(date)
        f.parentFile?.mkdirs()
        val obj = buildJsonObject {
            put("source", r.source)
            put("fetched_at", r.fetchedAt.toString())
            put("matches", buildJsonArray {
                r.matches.forEach { m ->
                    add(buildJsonObject {
                        put("id", m.id)
                        m.kickoff?.let { put("kickoff_ms", it.toString()) }
                        m.competition?.let { put("competition", it) }
                        put("home", m.home)
                        put("away", m.away)
                        put("channels", buildJsonArray { m.broadcasters.forEach { add(JsonPrimitive(it)) } })
                        m.url?.let { put("url", it) }
                        m.league?.let { put("league", it) }
                        m.homeLogo?.let { put("home_logo", it) }
                        m.awayLogo?.let { put("away_logo", it) }
                    })
                }
            })
        }
        f.writeText(obj.toString())
        // Keep a week.
        f.parentFile?.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }?.forEach { it.delete() }
    }

    private fun fromServer(date: String): List<Match> {
        val base = prefs.matchesUrl()?.toHttpUrlOrNull() ?: throw IOException("No server set")
        val url = base.newBuilder().setQueryParameter("date", date).build()
        val req = Request.Builder().url(url).header("X-Restv-Key", prefs.matchesKey() ?: "").build()
        val body = http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("the server rejected the key")
            if (!resp.isSuccessful) throw IOException("server answered ${resp.code}")
            resp.body?.string() ?: throw IOException("empty answer")
        }
        return parseMatches(json.parseToJsonElement(body).jsonObject)
    }

    private fun parseMatches(root: JsonObject): List<Match> {
        val list = root["matches"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Match(
                id = o.str("id") ?: return@mapNotNull null,
                kickoff = o.str("kickoff_ms")?.toLongOrNull()
                    ?: o.str("kickoff")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
                competition = o.str("competition"),
                home = o.str("home") ?: "",
                away = o.str("away") ?: "",
                broadcasters = (o["channels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                url = o.str("url"),
                league = o.str("league"),
                homeLogo = o.str("home_logo"),
                awayLogo = o.str("away_logo"),
            )
        }
    }

    /** Everything shown for one match: the user's channels from every country, plus crests. */
    data class Coverage(
        val channels: List<ChannelEntity>,
        /** Countries each found channel is broadcast in, by streamId ("Great Britain", "Australia"). */
        val countries: Map<Long, List<String>>,
        val missing: List<String>,
        val homeLogo: String?,
        val awayLogo: String?,
        val worldwide: Boolean,
    )

    suspend fun coverage(loginId: Long, match: Match): Coverage = withContext(Dispatchers.IO) {
        val detail = match.url?.let { url -> runCatching { detail(match.id, url, match.home, match.away) }.getOrNull() }
        // (country, broadcaster name), only from the countries the user watches.
        val pairs = LinkedHashSet<Pair<Broadcasters.Country, String>>()
        detail?.coverage?.forEach { (country, name) -> countryOf(country)?.let { pairs += it to name } }
        // The schedule page lists the viewer's own region (Iraq) — Shahid, STARZPLAY, Thmanyah… — add it too.
        match.broadcasters.forEach { pairs += iraq to it }

        val idx = index(loginId)
        val comp = Broadcasters.competition(match.league, match.competition)
        val found = LinkedHashMap<Long, ChannelEntity>()
        val countries = HashMap<Long, MutableList<String>>()
        val missing = LinkedHashSet<String>()
        // Per country: named channels first ("Sky Sports Main Event"), then network names ("Sky Go"),
        // which are skipped when that network's channel is already known for the country.
        for ((country, list) in pairs.groupBy({ it.first }, { it.second })) {
            val (generic, named) = list.partition { Broadcasters.brand(country, core(it)) != null }
            val inCountry = ArrayList<ChannelEntity>()
            for (name in named + generic) {
                val brand = Broadcasters.brand(country, core(name))
                if (brand != null && inCountry.any { ch -> brand.anyRx.any { it.matches(core(Naming.cleanChannelName(ch.rawName))) } }) continue
                val hits = resolve(idx, country, name, comp).filter { ch ->
                    val code = Naming.split(ch.rawName).code
                    code !in knownCountryCodes || code in country.codes
                }
                if (hits.isEmpty()) { missing += name; continue }
                // The same channel often comes from 10+ sources: keep the best three of each, 4K first.
                val best = hits.groupBy { core(Naming.cleanChannelName(it.rawName)) }.values
                    .flatMap { copies -> copies.sortedByDescending { it.quality }.take(3) }
                inCountry += best
                for (ch in best) {
                    found[ch.streamId] = ch
                    val labels = countries.getOrPut(ch.streamId) { ArrayList() }
                    if (country.label !in labels) labels += country.label
                }
            }
        }
        if (detail != null) {
            detail.homeLogo?.let { learnLogo(match.home, it) }
            detail.awayLogo?.let { learnLogo(match.away, it) }
        }
        Coverage(
            found.values.toList(), countries, missing.toList(),
            detail?.homeLogo ?: logoFor(match, home = true),
            detail?.awayLogo ?: logoFor(match, home = false),
            detail != null,
        )
    }

    /** Channels for one broadcaster in one country; public for tests. */
    suspend fun channelsFor(loginId: Long, country: String, broadcaster: String, league: String?, competition: String?): List<ChannelEntity> {
        val c = countryOf(country) ?: return emptyList()
        return resolve(index(loginId), c, broadcaster, Broadcasters.competition(league, competition)).filter { ch ->
            val code = Naming.split(ch.rawName).code
            code !in knownCountryCodes || code in c.codes
        }
    }

    // ---- Countries the user follows: the UK, the US, Canada, Europe (Broadcasters) and the Arab region.
    // Provider prefixes from other countries are a different feed (beIN Sports 3 Australia is not
    // beIN Sports 3 MENA), so those are skipped.

    private val arabCodes = setOf("AR", "ARB", "ARA", "IQ", "KU", "KRD", "MENA", "SA", "KSA", "AE", "UAE", "QA", "KW", "JO", "BH", "OM")
    private val iraq = Broadcasters.Country("iraq", "Iraq", listOf("iraq"), arabCodes, emptyList())
    private val saudi = Broadcasters.Country("saudi", "Saudi Arabia", listOf("saudi arabia"), arabCodes, emptyList())

    private fun countryOf(name: String): Broadcasters.Country? = when (Broadcasters.fold(name).trim().lowercase()) {
        "iraq" -> iraq
        "saudi arabia" -> saudi
        else -> Broadcasters.country(name)
    }

    /** Prefixes that name a country. Anything else ("NM", "SS", "VIP") is a provider tag and is allowed. */
    private val knownCountryCodes = arabCodes + Broadcasters.countries.flatMap { it.codes } + setOf(
        "PT", "NL", "BE", "TR", "IN", "PK", "IR", "EG", "MA", "DZ", "TN", "AU", "BR", "MX", "PL", "RU", "NO",
        "DK", "GR", "AL", "PH", "NZ", "IE", "AT", "CH", "LB", "SY", "AZ", "RO", "HU", "CZ", "SK", "HR", "RS",
        "BG", "UA", "IL", "ZA", "NG", "KE", "GH", "JP", "KR", "CN", "HK", "SG", "MY", "ID", "TH", "VN", "AF",
        "LAT", "ARG", "CL", "CO", "PE", "EX", "EXYU", "BIH", "MK", "SI", "LT", "LV", "EE", "IS", "CY", "MT",
        "ES", "FR", "DE", "IT", "UK", "US", "CA", "SE", "FI",
    )

    // ---- League priority: the user's leagues first, in his order; everything else after.

    fun priority(m: Match): Int {
        val l = m.league.orEmpty().lowercase()
        val c = fold(m.competition.orEmpty())
        val t = "$l $c"
        if (listOf("women", "femen", "u17", "u19", "u20", "u21", "u23", "youth", "premier-league-2", "premier league 2", "reserve").any { it in t }) return 99
        if (isFavorite(m)) return -1
        val teams = fold("${m.home} ${m.away}")
        return when {
            ("england" in l && l.endsWith("premier-league")) || (l.isEmpty() && c == "premier league") -> 0
            ("real madrid" in teams && "castilla" !in teams) ||
                ("spain" in l && listOf("primera", "la-liga", "laliga").any { it in l }) || "la liga" in c || "laliga" in c -> 1
            ("champions-league" in l || "champions league" in c) && listOf("afc", "caf", "concacaf", "asia", "africa").none { it in t } -> 2
            ("germany" in l && l.endsWith("/bundesliga")) || (l.isEmpty() && c == "bundesliga") -> 3
            ("italy" in l && l.endsWith("serie-a")) || (l.isEmpty() && c == "serie a") -> 4
            ("france" in l && l.endsWith("ligue-1")) || (l.isEmpty() && c == "ligue 1") -> 5
            "saudi" in t -> 6
            "iraq" in t -> 7
            ("turkey" in l && "super-lig" in l) || "super lig" in c -> 8
            else -> 99
        }
    }

    private fun fold(s: String) = Broadcasters.fold(s).lowercase()

    /** A match of one of the teams picked in Settings → Favourite teams. */
    fun isFavorite(m: Match): Boolean {
        val favs = prefs.favoriteTeams.value
        if (favs.isEmpty()) return false
        val keys = favs.map(Teams::key).toSet()
        return Teams.key(m.home) in keys || Teams.key(m.away) in keys
    }

    /** Set by Home's match card: the Matches page opens on this match. */
    @Volatile var openMatchId: String? = null

    // ---- Team crests: from the schedule row, crests learned from match pages, or a guess from the
    // league's country and the team's slug in the match link.

    private val logoFile = File(cacheDir, "matches/logos.json")
    private val learned: MutableMap<String, String> by lazy {
        val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        runCatching {
            json.parseToJsonElement(logoFile.readText()).jsonObject.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { map[k] = it } }
        }
        map
    }

    private fun learnLogo(team: String, url: String) {
        if (learned.put(team.lowercase(), url) == url) return
        runCatching {
            logoFile.parentFile?.mkdirs()
            logoFile.writeText(buildJsonObject { learned.forEach { (k, v) -> put(k, v) } }.toString())
        }
    }

    fun logoFor(m: Match, home: Boolean): String? {
        (if (home) m.homeLogo else m.awayLogo)?.let { return it }
        learned[(if (home) m.home else m.away).lowercase()]?.let { return it }
        val country = m.league?.split('/')?.takeIf { it.size >= 2 }?.first()
            ?.takeIf { it !in setOf("international", "europe", "world", "asia", "africa", "south-america", "north-america", "club-friendlies") }
            ?: return null
        val slugs = m.url?.trimEnd('/')?.substringAfter("/match/", "")?.substringBefore('/')?.split("-vs-")
            ?.takeIf { it.size == 2 } ?: return null
        return "https://cdn.livesoccertv.com/tt/images/teams/$country/logos/${if (home) slugs[0] else slugs[1]}.png"
    }

    /**
     * Finds the channels for one broadcaster name in one country:
     * 1. a network or app name in that country ("DAZN", "Movistar+", "Sky Go", "Canal+") → only that
     *    network's sports channels, the ones for this competition when known (Broadcasters);
     * 2. the same core name ("beIN Sports 3" ↔ "AR: beIN SP⚽RTS 3 HD", "Sky Sports Main Event");
     * 3. Arab streaming apps ("STARZPLAY", "Shahid VIP", "Thmanyah") → their numbered sports channels,
     *    spelling folded ("Thamanya 1", "ALMANASA").
     */
    private fun resolve(idx: Index, country: Broadcasters.Country, broadcaster: String, comp: String): List<ChannelEntity> {
        val target = core(broadcaster)
        if (target.isEmpty() || target in never) return emptyList()
        Broadcasters.brand(country, target)?.let { brand ->
            brand.compRx[comp]?.let { specific -> idx.matching(specific).takeIf { it.isNotEmpty() }?.let { return it } }
            return idx.matching(brand.anyRx)
        }
        // Exact name. A general name ("beIN SPORTS", "Movistar+") must not pull in every channel of a family.
        idx.byCore[target]?.let { return it }
        val sk = skeleton(target)
        if (appBrands.none { sk.startsWith(it) }) return emptyList()
        val prefix = idx.byCore.subMap("$target ", "$target \uffff").values.flatten()
        if (prefix.isNotEmpty()) return sportsFirst(prefix)
        idx.bySkeleton[sk]?.let { return sportsFirst(it) }
        val loose = idx.bySkeleton.filterKeys { it.startsWith(sk) && it.length > sk.length && it[sk.length].isDigit() || it.startsWith(sk + "sp") }.values.flatten()
        return sportsFirst(loose)
    }

    /** Services named by their app that carry several numbered sports channels. */
    private val appBrands = listOf("shahid", "starzplay", "thmanyah", "al manassa", "manasa", "jawwy", "stc tv").map { skeleton(it) }

    private fun sportsFirst(list: List<ChannelEntity>): List<ChannelEntity> {
        val sports = list.filter { "sport" in it.name.lowercase() || "رياض" in it.name }
        return sports.ifEmpty { list.take(6) }
    }

    /** Spelling-insensitive form: no spaces, "al/el" prefixes, vowels after the first letter, doubles or final h. */
    fun skeleton(core: String): String =
        core.split(' ').filter { it.isNotEmpty() && it != "al" && it != "el" }.joinToString("") { w ->
            val word = if ((w.startsWith("al") || w.startsWith("el")) && w.length > 5 && w[2].isLetter()) w.drop(2) else w
            val body = word.first() + word.drop(1).filterNot { it in "aeiouy" }
            body.replace(Regex("""(.)\1+"""), "$1").let { if (it.length > 3 && it.endsWith("h")) it.dropLast(1) else it }
        }

    private class Index(val byCore: java.util.TreeMap<String, List<ChannelEntity>>, val bySkeleton: Map<String, List<ChannelEntity>>) {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

        /** Channels whose core name fully matches one of [patterns], in pattern order. */
        fun matching(patterns: List<Regex>): List<ChannelEntity> = patterns.flatMap { rx ->
            cache.getOrPut(rx.pattern) {
                val lit = literalPrefix(rx.pattern)
                val keys = if (lit.isEmpty()) byCore.keys else byCore.subMap(lit, lit + "\uffff").keys
                keys.filter { rx.matches(it) }
            }.flatMap { byCore[it].orEmpty() }
        }.distinctBy { it.streamId }

        /** Leading plain text of a pattern ("sky sports (main|…)" → "sky sports "), to search fewer names. */
        private fun literalPrefix(p: String): String {
            val sb = StringBuilder()
            for (i in p.indices) {
                val ch = p[i]
                if (!(ch.isLetterOrDigit() || ch == ' ')) {
                    if (ch == '?' || ch == '*' || ch == '{') sb.setLength((sb.length - 1).coerceAtLeast(0))
                    break
                }
                sb.append(ch)
            }
            return sb.toString()
        }
    }

    // Channel list reduced to core names, rebuilt when the list changes (sync).
    private val indexLock = Mutex()
    private var indexKey: Pair<Long, Int>? = null
    private var indexMap = Index(java.util.TreeMap(), emptyMap())

    private suspend fun index(loginId: Long): Index = indexLock.withLock {
        val key = loginId to db.live().countChannels(loginId)
        if (key != indexKey) {
            val byCore = java.util.TreeMap(db.live().allChannels(loginId)
                .filterNot { Naming.isSeparator(it.rawName) }
                .groupBy { core(Naming.cleanChannelName(it.rawName)) })
            val bySkeleton = HashMap<String, MutableList<ChannelEntity>>()
            byCore.forEach { (k, v) -> bySkeleton.getOrPut(skeleton(k)) { ArrayList() }.addAll(v) }
            indexMap = Index(byCore, bySkeleton)
            indexKey = key
        }
        indexMap
    }

    private val details = HashMap<String, Pair<Long, LiveSoccerTv.Detail>>()

    private suspend fun detail(id: String, url: String, home: String, away: String): LiveSoccerTv.Detail {
        val now = System.currentTimeMillis()
        synchronized(details) { details[id] }?.let { if (now - it.first < 3 * 3_600_000L) return it.second }
        val f = File(cacheDir, "matches/detail-$id.json")
        if (f.exists() && now - f.lastModified() < 3 * 3_600_000L) {
            runCatching {
                val o = json.parseToJsonElement(f.readText()).jsonObject
                val cov = (o["coverage"] as? JsonArray)?.mapNotNull { el ->
                    val arr = el as? JsonArray ?: return@mapNotNull null
                    val c = (arr.getOrNull(0) as? JsonPrimitive)?.content ?: ""
                    val n = (arr.getOrNull(1) as? JsonPrimitive)?.content ?: return@mapNotNull null
                    c to n
                }.orEmpty()
                LiveSoccerTv.Detail(cov, o.str("home_logo"), o.str("away_logo"))
            }.getOrNull()?.let { d ->
                synchronized(details) { details[id] = f.lastModified() to d }
                return d
            }
        }
        val d = lstv.fetchDetail(url, home, away)
        synchronized(details) { details[id] = now to d }
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(buildJsonObject {
                put("coverage", buildJsonArray { d.coverage.forEach { (c, n) -> add(buildJsonArray { add(JsonPrimitive(c)); add(JsonPrimitive(n)) }) } })
                d.homeLogo?.let { put("home_logo", it) }
                d.awayLogo?.let { put("away_logo", it) }
            }.toString())
        }
        return d
    }

    /** "beIN Sports Arabia 5 HD" and "beIN SPORTS 5 4K" both become "bein sports 5" (see [Broadcasters.core]). */
    private fun core(name: String): String = Broadcasters.core(name)

    /** Apps, not channels: nothing in an IPTV list carries them. */
    private val never = setOf("bein sports connect", "bein connect")

}
