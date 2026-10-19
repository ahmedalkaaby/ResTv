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
import java.time.OffsetDateTime

data class Match(
    val id: String,
    val kickoff: Long?,
    val competition: String?,
    val home: String,
    val away: String,
    val broadcasters: List<String>,
)

/** Reads the day's matches from the user's Restv server and maps broadcasters to their own channels. */
class MatchesRepo(
    private val http: OkHttpClient,
    private val json: Json,
    private val db: AppDatabase,
    private val prefs: Prefs,
) {
    suspend fun fetch(date: String): List<Match> = withContext(Dispatchers.IO) {
        val base = prefs.matchesUrl()?.toHttpUrlOrNull() ?: throw IOException("Add your matches server in Settings first")
        val url = base.newBuilder().setQueryParameter("date", date).build()
        val req = Request.Builder().url(url).header("X-Restv-Key", prefs.matchesKey() ?: "").build()
        val body = http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("The server rejected the key. Check it in Settings.")
            if (!resp.isSuccessful) throw IOException("Matches server answered ${resp.code}")
            resp.body?.string() ?: throw IOException("Empty answer from the matches server")
        }
        val root = json.parseToJsonElement(body).jsonObject
        val list = root["matches"] as? JsonArray ?: return@withContext emptyList()
        list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Match(
                id = o.str("id") ?: return@mapNotNull null,
                kickoff = o.str("kickoff")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
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
