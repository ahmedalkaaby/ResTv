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
        for (candidate in candidates(broadcaster)) {
            val key = norm(candidate)
            if (key.length < 3) continue
            val hits = db.live().search(loginId, candidate).filter { ch ->
                val n = norm(Naming.cleanChannelName(ch.rawName))
                // "bein sports 1" must not match "bein sports 10".
                n.startsWith(key) && n.getOrNull(key.length)?.isDigit() != true ||
                    norm(ch.rawName).contains(key) && !norm(ch.rawName).substringAfter(key).take(1).any { it.isDigit() }
            }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun candidates(name: String): List<String> {
        val dropWords = setOf("mena", "arabia", "arabic", "ar", "hd", "english", "french", "francais", "uk", "usa", "us", "tv", "channel")
        val words = name.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        val out = mutableListOf(name)
        while (words.size > 1 && words.last().lowercase() in dropWords) {
            words.removeAt(words.lastIndex)
            out += words.joinToString(" ")
        }
        return out.distinct()
    }

    private fun norm(s: String): String = Naming.normalize(s).lowercase().filter { it.isLetterOrDigit() }
}
