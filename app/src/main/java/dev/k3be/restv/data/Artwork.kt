package dev.k3be.restv.data

import dev.k3be.restv.data.db.LoginEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Wide artwork (no text) and the title logo for a movie or show. */
data class Art(val backdrop: String?, val logo: String?)

/**
 * Artwork for wide cards and title logos. With a TMDB key (free, themoviedb.org) it uses TMDB's
 * backdrops and logos; without one it falls back to the provider's own backdrop.
 * Results are kept on disk, so each title is looked up once.
 */
class Artwork(
    private val http: OkHttpClient,
    private val json: Json,
    private val dir: File,
    private val prefs: Prefs,
    private val vod: VodRepo,
    private val apiBase: String = "https://api.themoviedb.org/3",
) {
    /** Set when a session starts; used for the provider fallback. */
    @Volatile var login: LoginEntity? = null

    private val mem = ConcurrentHashMap<String, Art>()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(4)
    private val file = File(dir, "artwork.json")
    @Volatile private var loaded = false
    @Volatile private var dirty = 0

    fun peek(kind: String, title: String, year: Int?): Art? {
        ensureLoaded()
        return mem[key(kind, title, year)]
    }

    suspend fun get(kind: String, id: Long, title: String, year: Int?, fallbackBackdrop: String? = null): Art = withContext(Dispatchers.IO) {
        ensureLoaded()
        val k = key(kind, title, year)
        mem[k]?.let { return@withContext it }
        if (k in misses) return@withContext Art(fallbackBackdrop, null)
        val art = gate.withPermit {
            val tmdbKey = prefs.tmdbKey()
            val fromTmdb = if (tmdbKey != null) runCatching { tmdb(tmdbKey, kind, title, year) }.getOrNull() else null
            fromTmdb?.takeIf { it.backdrop != null || it.logo != null }
                ?: Art(fallbackBackdrop ?: providerBackdrop(kind, id), null)
        }
        if (art.backdrop == null && art.logo == null) misses += k
        else { mem[k] = art; save() }
        art
    }

    private suspend fun providerBackdrop(kind: String, id: Long): String? {
        val l = login ?: return null
        return runCatching {
            if (kind == "movie") vod.movieInfo(l, id).backdrop else vod.seriesInfo(l, id).backdrop
        }.getOrNull()
    }

    private fun tmdb(key: String, kind: String, title: String, year: Int?): Art? {
        val type = if (kind == "movie") "movie" else "tv"
        val search = "$apiBase/search/$type".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key)
            .addQueryParameter("query", title)
            .apply { if (year != null) addQueryParameter(if (type == "movie") "year" else "first_air_date_year", year.toString()) }
            .build()
        val results = getJson(search.toString())?.get("results") as? JsonArray ?: return null
        val id = (results.firstOrNull() as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.content } ?: return null
        val images = "$apiBase/$type/$id/images".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key)
            .addQueryParameter("include_image_language", "en,null,ar")
            .build()
        val root = getJson(images.toString()) ?: return null
        fun pick(arrKey: String, langs: List<String?>): String? {
            val list = (root[arrKey] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            for (lang in langs) {
                val best = list.filter { (it["iso_639_1"] as? JsonPrimitive)?.content.let { l -> if (lang == null) l == null || l == "null" else l == lang } }
                    .maxByOrNull { (it["vote_average"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0 }
                val path = (best?.get("file_path") as? JsonPrimitive)?.content
                if (path != null) return path
            }
            return null
        }
        val backdrop = pick("backdrops", listOf(null, "en"))?.let { "https://image.tmdb.org/t/p/w780$it" }
        val logo = pick("logos", listOf("en", null, "ar"))?.let { "https://image.tmdb.org/t/p/w500$it" }
        return Art(backdrop, logo)
    }

    private fun getJson(url: String): JsonObject? = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) return null
        runCatching { json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }.getOrNull()
    }

    private fun key(kind: String, title: String, year: Int?) = "$kind|${VodNaming.key(title)}|${year ?: ""}"

    /** Forget everything (after adding a TMDB key, so titles get logos). */
    fun reset() {
        mem.clear(); misses.clear(); file.delete()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val o = json.parseToJsonElement(file.readText()).jsonObject
                o.forEach { (k, v) ->
                    val a = v as? JsonObject ?: return@forEach
                    mem[k] = Art((a["b"] as? JsonPrimitive)?.content, (a["l"] as? JsonPrimitive)?.content)
                }
            }
            loaded = true
        }
    }

    private fun save() {
        // Write every few new titles; it's only a cache.
        if (++dirty % 8 != 0) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(buildJsonObject {
                mem.forEach { (k, a) -> put(k, buildJsonObject { a.backdrop?.let { put("b", it) }; a.logo?.let { put("l", it) } }) }
            }.toString())
        }
    }

    fun flush() { dirty = 7; save() }
}
