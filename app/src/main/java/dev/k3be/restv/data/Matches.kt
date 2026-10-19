package dev.k3be.restv.data

import dev.k3be.restv.data.db.AppDatabase
import dev.k3be.restv.data.db.ChannelEntity
import dev.k3be.restv.data.xtream.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private fun cacheFile(date: String) = File(cacheDir, "matches/$date.json")

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
            )
        }
    }

    /**
     * Finds the user's channels for each broadcaster name ("beIN SPORTS 1 MENA" → "beIN Sports 1 4K", …).
     * Tries the full name, then drops region words like MENA/Arabia from the end.
     */
    suspend fun channelsFor(loginId: Long, broadcasters: List<String>): Pair<List<ChannelEntity>, List<String>> {
        val found = LinkedHashMap<Long, ChannelEntity>()
        val missing = ArrayList<String>()
        for (b in broadcasters) {
            val hits = matchOne(loginId, b)
            if (hits.isEmpty()) missing += b else hits.forEach { found[it.streamId] = it }
        }
        return found.values.toList() to missing
    }

    private suspend fun matchOne(loginId: Long, broadcaster: String): List<ChannelEntity> {
        val target = core(broadcaster)
        if (target.isEmpty() || streamingOnly.any { target == it || target.startsWith("$it ") }) return emptyList()
        val words = target.split(' ')
        // Search the database by the most distinctive word, then compare cleaned names exactly.
        val probe = words.firstOrNull { it.length >= 3 && it.none(Char::isDigit) } ?: words.first()
        return db.live().searchAll(loginId, probe).filter { ch -> core(Naming.cleanChannelName(ch.rawName)) == target }
    }

    /**
     * Reduces a channel name to what identifies it: "beIN Sports Arabia 5 HD" and "beIN SPORTS 5 4K"
     * both become "bein sports 5". Region and quality words are dropped wherever they appear.
     */
    private fun core(name: String): String =
        Naming.normalize(name).lowercase()
            .replace(Regex("""\(.*?\)"""), " ")
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotBlank() && it !in noise }
            .joinToString(" ")

    private val noise = setOf(
        "mena", "arabia", "arabic", "ar", "hd", "fhd", "uhd", "4k", "sd", "hevc", "tv", "channel", "live",
        "max", "premium", "global", "international", "middle", "east", "north", "africa",
    )

    /** Streaming services that will never be an IPTV channel in the list. */
    private val streamingOnly = listOf("tod", "dazn", "bein sports connect", "shahid", "fubo", "peacock", "paramount", "espn plus")

}
