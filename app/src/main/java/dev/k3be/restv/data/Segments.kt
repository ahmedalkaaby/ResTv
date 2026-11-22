package dev.k3be.restv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** A part of an episode or film that can be skipped, in milliseconds. [end] null = to the end. */
data class Segment(val kind: String, val start: Long, val end: Long?)

/**
 * Intro, recap, credits and preview times from TheIntroDB (theintrodb.org), a community database
 * keyed by TMDB id. Reading is free and needs no key. Intro data provided by TheIntroDB.
 */
class Segments(
    private val http: OkHttpClient,
    private val json: Json,
    private val base: String = "https://api.theintrodb.org/v4/media",
) {
    private val cache = ConcurrentHashMap<String, List<Segment>>()
    @Volatile private var pausedUntil = 0L

    suspend fun get(tmdbId: String, season: Int?, episode: Int?, durationMs: Long?): List<Segment> = withContext(Dispatchers.IO) {
        val key = "$tmdbId|${season ?: ""}|${episode ?: ""}"
        cache[key]?.let { return@withContext it }
        if (System.currentTimeMillis() < pausedUntil) return@withContext emptyList()
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("tmdb_id", tmdbId)
            .apply {
                if (season != null && episode != null) {
                    addQueryParameter("season", season.toString()); addQueryParameter("episode", episode.toString())
                }
                if (durationMs != null && durationMs > 0) addQueryParameter("duration_ms", durationMs.toString())
            }.build()
        val result = runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                when {
                    // Over the free limit: wait before asking again (the answer says how long, else an hour).
                    r.code == 429 -> {
                        val wait = runCatching {
                            (json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["retry_after_seconds"] as? JsonPrimitive)?.content?.toLong()
                        }.getOrNull() ?: 3600
                        pausedUntil = System.currentTimeMillis() + wait.coerceIn(60, 86_400) * 1000
                        null
                    }
                    r.code == 404 || r.code == 400 -> emptyList()
                    !r.isSuccessful -> null
                    else -> parse(json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject, durationMs)
                }
            }
        }.getOrNull()
        result?.also { cache[key] = it } ?: emptyList()
    }

    internal fun parse(o: JsonObject, durationMs: Long?): List<Segment> = buildList {
        for (kind in listOf("intro", "recap", "credits", "preview")) {
            val list = (o[kind] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            // The best-voted entry comes first.
            val seg = list.firstOrNull() ?: continue
            fun num(k: String) = (seg[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }?.toDoubleOrNull()?.toLong()
            val start = num("start_ms") ?: 0L
            var end = num("end_ms")
            if (start == 0L && end == 0L) continue
            if (end == 0L) end = null
            if (end != null && durationMs != null && durationMs > 0 && end > durationMs) end = durationMs
            if (end != null && end <= start) continue
            add(Segment(kind, start, end))
        }
    }
}
