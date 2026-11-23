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

/** Someone in the cast or crew. */
data class Person(val name: String, val role: String?, val photo: String?)

/** Everything a details page shows beyond the provider's info, from TMDB. */
data class TitleDetails(
    val id: String?,
    val tagline: String?,
    val overview: String?,
    val runtimeMin: Int?,
    val genres: List<String>,
    val released: String?,
    val score: Double?,
    val votes: Int?,
    val certification: String?,
    val language: String?,
    val directors: List<String>,
    val writers: List<String>,
    val studios: List<String>,
    val status: String?,
    val seasons: Int?,
    val episodes: Int?,
    val cast: List<Person>,
    /** Recommended titles (title, year), to match against the catalogue. */
    val similar: List<Pair<String, Int?>>,
    /** Trailers and clips on YouTube, trailers first. */
    val videos: List<Video> = emptyList(),
    /** Season number → poster (series). */
    val seasonPosters: Map<Int, String> = emptyMap(),
)

/** A YouTube trailer, teaser or clip. */
data class Video(val key: String, val name: String, val type: String) {
    val thumb: String get() = "https://img.youtube.com/vi/$key/hqdefault.jpg"
}

/** TMDB's facts about one episode: still, synopsis, rating and air date. */
data class EpisodeMeta(val number: Int, val name: String?, val overview: String?, val still: String?, val score: Double?, val airDate: String?, val runtimeMin: Int?)

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
    // v2: earlier versions saved "no logo" results from before a TMDB key worked; start clean.
    private val file = File(dir, "artwork-v2.json").also { File(dir, "artwork.json").delete() }
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
        var fromTmdb: Art? = null
        val art = gate.withPermit {
            val tmdbKey = prefs.tmdbKey()
            fromTmdb = if (tmdbKey != null) runCatching { tmdb(tmdbKey, kind, title, year) }
                .onFailure { lastError = "Couldn't reach TMDB (${it.javaClass.simpleName})" }.getOrNull() else null
            if (fromTmdb != null) found++
            fromTmdb?.takeIf { it.backdrop != null || it.logo != null }
                ?: Art(fallbackBackdrop ?: providerBackdrop(kind, id), null)
        }
        when {
            // Only TMDB's answers are kept on disk; a fallback is retried next launch.
            fromTmdb != null && (art.backdrop != null || art.logo != null) -> { mem[k] = art; save() }
            art.backdrop == null -> misses += k
            else -> mem[k] = art
        }
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
        fun search(withYear: Boolean): JsonArray? {
            val url = "$apiBase/search/$type".toHttpUrl().newBuilder()
                .withKey(key)
                .addQueryParameter("query", title)
                .apply { if (withYear && year != null) addQueryParameter(if (type == "movie") "year" else "first_air_date_year", year.toString()) }
                .build()
            return getJson(url.toString(), key)?.get("results") as? JsonArray
        }
        // The provider's year is sometimes off by one or missing: retry without it before giving up.
        val results = search(true)?.takeIf { it.isNotEmpty() } ?: (if (year != null) search(false) else null) ?: return null
        val id = (results.firstOrNull() as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.content } ?: return null
        val images = "$apiBase/$type/$id/images".toHttpUrl().newBuilder()
            .withKey(key)
            .addQueryParameter("include_image_language", "en,null,ar")
            .build()
        val root = getJson(images.toString(), key) ?: return null
        fun pick(arrKey: String, langs: List<String?>): String? {
            // SVG logos can't be drawn by the image loader: PNG/JPG only.
            val list = (root[arrKey] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                .filter { (it["file_path"] as? JsonPrimitive)?.content?.lowercase()?.endsWith(".svg") != true }
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

    private val detailsCache = ConcurrentHashMap<String, TitleDetails>()

    /**
     * Cast, crew and facts for a details page. Uses the provider's TMDB id when it has one,
     * otherwise searches by title and year. Null without a TMDB key or when nothing matches.
     */
    suspend fun details(kind: String, title: String, year: Int?, tmdbId: String?): TitleDetails? = withContext(Dispatchers.IO) {
        val key = prefs.tmdbKey() ?: return@withContext null
        val type = if (kind == "movie") "movie" else "tv"
        val cacheKey = "$type|${tmdbId ?: ""}|${VodNaming.key(title)}|${year ?: ""}"
        detailsCache[cacheKey]?.let { return@withContext it }
        runCatching {
            val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: run {
                fun search(withYear: Boolean): JsonArray? {
                    val url = "$apiBase/search/$type".toHttpUrl().newBuilder().withKey(key)
                        .addQueryParameter("query", title)
                        .apply { if (withYear && year != null) addQueryParameter(if (type == "movie") "year" else "first_air_date_year", year.toString()) }
                        .build()
                    return getJson(url.toString(), key)?.get("results") as? JsonArray
                }
                val results = search(true)?.takeIf { it.isNotEmpty() } ?: (if (year != null) search(false) else null)
                (results?.firstOrNull() as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.content }
            } ?: return@runCatching null
            val extra = if (type == "movie") "credits,release_dates,recommendations,videos" else "aggregate_credits,content_ratings,recommendations,videos"
            val url = "$apiBase/$type/$id".toHttpUrl().newBuilder().withKey(key)
                .addQueryParameter("append_to_response", extra).build()
            val o = getJson(url.toString(), key) ?: return@runCatching null
            parseDetails(o, type)
        }.getOrNull()?.also { detailsCache[cacheKey] = it }
    }

    private val seasonCache = java.util.concurrent.ConcurrentHashMap<String, List<EpisodeMeta>>()

    /** One season's episodes from TMDB (empty without a key or on any failure). */
    suspend fun season(tmdbId: String, number: Int): List<EpisodeMeta> = withContext(Dispatchers.IO) {
        val key = prefs.tmdbKey() ?: return@withContext emptyList()
        seasonCache["$tmdbId|$number"]?.let { return@withContext it }
        runCatching {
            val url = "$apiBase/tv/$tmdbId/season/$number".toHttpUrl().newBuilder().withKey(key).build()
            getJson(url.toString(), key)?.let(::parseSeason)
        }.getOrNull()?.also { seasonCache["$tmdbId|$number"] = it } ?: emptyList()
    }

    internal fun parseSeason(o: JsonObject): List<EpisodeMeta> {
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        return (o["episodes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { e ->
            EpisodeMeta(
                number = e.s("episode_number")?.toIntOrNull() ?: return@mapNotNull null,
                name = e.s("name"),
                overview = e.s("overview"),
                still = e.s("still_path")?.let { "https://image.tmdb.org/t/p/w300$it" },
                score = e.s("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 },
                airDate = e.s("air_date"),
                runtimeMin = e.s("runtime")?.toIntOrNull(),
            )
        }
    }

    private fun parseDetails(o: JsonObject, type: String): TitleDetails {
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        fun JsonObject.arr(k: String) = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val img = { p: String? -> p?.let { "https://image.tmdb.org/t/p/w185$it" } }
        val castObjs = if (type == "movie") (o["credits"] as? JsonObject)?.arr("cast") else (o["aggregate_credits"] as? JsonObject)?.arr("cast")
        val cast = castObjs.orEmpty().take(20).mapNotNull { c ->
            val name = c.s("name") ?: return@mapNotNull null
            val role = c.s("character") ?: (c["roles"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("character") }?.firstOrNull()
            Person(name, role, img(c.s("profile_path")))
        }
        val crew = if (type == "movie") (o["credits"] as? JsonObject)?.arr("crew").orEmpty() else emptyList()
        val directors = if (type == "movie") crew.filter { it.s("job") == "Director" }.mapNotNull { it.s("name") }.distinct()
            else o.arr("created_by").mapNotNull { it.s("name") }
        val writers = crew.filter { it.s("department") == "Writing" }.mapNotNull { it.s("name") }.distinct().take(3)
        val cert = if (type == "movie") {
            val rel = (o["release_dates"] as? JsonObject)?.arr("results").orEmpty()
            fun forCountry(c: String) = rel.firstOrNull { it.s("iso_3166_1") == c }?.arr("release_dates")?.mapNotNull { it.s("certification") }?.firstOrNull()
            forCountry("US") ?: forCountry("GB")
        } else {
            val r = (o["content_ratings"] as? JsonObject)?.arr("results").orEmpty()
            r.firstOrNull { it.s("iso_3166_1") == "US" }?.s("rating") ?: r.firstOrNull { it.s("iso_3166_1") == "GB" }?.s("rating")
        }
        val similar = (o["recommendations"] as? JsonObject)?.arr("results").orEmpty().mapNotNull { r ->
            val t = r.s("title") ?: r.s("name") ?: return@mapNotNull null
            t to (r.s("release_date") ?: r.s("first_air_date"))?.take(4)?.toIntOrNull()
        }
        val studios = (if (type == "movie") o.arr("production_companies") else o.arr("networks")).mapNotNull { it.s("name") }.take(3)
        val runtime = o.s("runtime")?.toIntOrNull() ?: (o["episode_run_time"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val order = listOf("Trailer", "Teaser", "Clip", "Featurette")
        val videos = (o["videos"] as? JsonObject)?.arr("results").orEmpty()
            .filter { it.s("site") == "YouTube" && it.s("key") != null && it.s("type") in order }
            .sortedWith(compareBy<JsonObject> { order.indexOf(it.s("type")) }.thenByDescending { it.s("official") == "true" })
            .map { Video(it.s("key")!!, it.s("name") ?: it.s("type")!!, it.s("type")!!) }
            .take(8)
        val seasonPosters = o.arr("seasons").mapNotNull { se ->
            val n = se.s("season_number")?.toIntOrNull() ?: return@mapNotNull null
            se.s("poster_path")?.let { n to "https://image.tmdb.org/t/p/w342$it" }
        }.toMap()
        return TitleDetails(
            videos = videos,
            seasonPosters = seasonPosters,
            id = o.s("id"),
            tagline = o.s("tagline"),
            overview = o.s("overview"),
            runtimeMin = runtime?.takeIf { it > 0 },
            genres = o.arr("genres").mapNotNull { it.s("name") },
            released = o.s("release_date") ?: o.s("first_air_date"),
            score = o.s("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 },
            votes = o.s("vote_count")?.toIntOrNull(),
            certification = cert,
            language = o.s("original_language")?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() } },
            directors = directors,
            writers = writers,
            studios = studios,
            status = o.s("status"),
            seasons = o.s("number_of_seasons")?.toIntOrNull(),
            episodes = o.s("number_of_episodes")?.toIntOrNull(),
            cast = cast,
            similar = similar,
        )
    }

    /**
     * TMDB gives two credentials: the short "API Key" (sent as ?api_key=) and the long
     * "API Read Access Token" (starts with eyJ, sent as a Bearer header). Either works here.
     */
    private fun isToken(key: String) = key.startsWith("eyJ")

    private fun okhttp3.HttpUrl.Builder.withKey(key: String) = apply { if (!isToken(key)) addQueryParameter("api_key", key) }

    private fun request(url: String, key: String) = Request.Builder().url(url)
        .apply { if (isToken(key)) header("Authorization", "Bearer $key") }
        .header("Accept", "application/json")
        .build()

    private fun getJson(url: String, key: String): JsonObject? = http.newCall(request(url, key)).execute().use { r ->
        if (r.code == 401) { lastError = "TMDB rejected the key"; return null }
        if (!r.isSuccessful) { lastError = "TMDB answered ${r.code}"; return null }
        lastError = null
        runCatching { json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }.getOrNull()
    }

    /** Why the last TMDB lookup failed, if it did. */
    @Volatile var lastError: String? = null

    /** Titles TMDB answered for in this session, and how many have a logo on file. */
    @Volatile var found = 0
    fun logoCount(): Int { ensureLoaded(); return mem.values.count { it.logo != null } }

    /** Null when the key works; otherwise what's wrong, in plain words. */
    suspend fun check(key: String): String? = withContext(Dispatchers.IO) {
        val k = key.trim()
        if (k.isEmpty()) return@withContext "No key entered"
        val url = "$apiBase/configuration".toHttpUrl().newBuilder().withKey(k).build().toString()
        try {
            http.newCall(request(url, k)).execute().use { r ->
                when {
                    r.isSuccessful -> null
                    r.code == 401 -> "TMDB says this key isn't valid. Copy the API Key (or the API Read Access Token) again."
                    else -> "TMDB answered ${r.code}"
                }
            }
        } catch (e: Exception) {
            "Couldn't reach TMDB from this TV (${e.javaClass.simpleName}). The internet connection or a block may be the cause."
        }
    }

    private fun key(kind: String, title: String, year: Int?) = "$kind|${VodNaming.key(title)}|${year ?: ""}"

    /**
     * Runs the whole chain for one well-known title and says how far it got, for Settings:
     * search → images → the logo file itself.
     */
    suspend fun diagnose(title: String = "Inception", year: Int = 2010): List<String> = withContext(Dispatchers.IO) {
        val key = prefs.tmdbKey() ?: return@withContext listOf("✗ No TMDB key saved")
        val out = ArrayList<String>()
        val art = runCatching { tmdb(key, "movie", title, year) }
        art.onFailure { out += "✗ Lookup failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}".trim() }
        val a = art.getOrNull()
        if (a == null) {
            out += "✗ TMDB found nothing for “$title”" + (lastError?.let { " ($it)" } ?: "")
            return@withContext out
        }
        out += "✓ TMDB found “$title”"
        out += if (a.logo != null) "✓ Logo: ${a.logo.substringAfterLast('/')}" else "✗ TMDB has no PNG logo for it"
        out += if (a.backdrop != null) "✓ Wide artwork found" else "✗ No wide artwork"
        val probe = a.logo ?: a.backdrop
        if (probe != null) {
            out += try {
                http.newCall(Request.Builder().url(probe).build()).execute().use { r ->
                    if (r.isSuccessful) "✓ Image server answered (${(r.body?.contentLength() ?: 0) / 1024} KB)"
                    else "✗ Image server answered ${r.code}"
                }
            } catch (e: Exception) {
                "✗ Couldn't reach the image server image.tmdb.org (${e.javaClass.simpleName}) — likely blocked on this connection"
            }
        }
        out
    }

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
