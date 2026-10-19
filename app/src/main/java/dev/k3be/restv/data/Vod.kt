package dev.k3be.restv.data

import dev.k3be.restv.data.db.AppDatabase
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.data.db.MovieEntity
import dev.k3be.restv.data.db.SeriesEntity
import dev.k3be.restv.data.xtream.XtreamClient
import dev.k3be.restv.data.xtream.int
import dev.k3be.restv.data.xtream.long
import dev.k3be.restv.data.xtream.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

data class MovieInfo(
    val title: String,
    val year: Int?,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val durationMs: Long,
    val rating: Float?,
    val backdrop: String?,
    val poster: String?,
    val ext: String?,
    val tmdbId: String? = null,
)

data class Episode(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationMs: Long,
    val ext: String,
)

data class Season(val number: Int, val name: String, val episodes: List<Episode>)

data class SeriesInfo(
    val title: String,
    val year: Int?,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val rating: Float?,
    val backdrop: String?,
    val poster: String?,
    val seasons: List<Season>,
    val tmdbId: String? = null,
)

/** Details from the provider, IMDb lists matched to the catalogue. */
class VodRepo(
    private val db: AppDatabase,
    private val xtream: XtreamClient,
    private val imdb: ImdbCharts,
) {
    private val movieCache = lru<Long, MovieInfo>(60)
    private val seriesCache = lru<Long, SeriesInfo>(30)

    suspend fun movieInfo(login: LoginEntity, id: Long): MovieInfo {
        synchronized(movieCache) { movieCache[id] }?.let { return it }
        val root = xtream.getObject(login, "get_vod_info", mapOf("vod_id" to id.toString()))
        val info = root["info"] as? JsonObject ?: JsonObject(emptyMap())
        val data = root["movie_data"] as? JsonObject
        val name = info.str("name") ?: data?.str("name") ?: ""
        val p = VodNaming.parse(name, info.str("releasedate") ?: info.str("release_date"))
        val m = MovieInfo(
            title = p.title,
            year = p.year,
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            cast = info.str("cast") ?: info.str("actors"),
            director = info.str("director"),
            durationMs = durationMs(info),
            rating = info.str("rating")?.toFloatOrNull()?.takeIf { it > 0 },
            backdrop = image(info["backdrop_path"]),
            poster = info.str("movie_image") ?: info.str("cover_big"),
            ext = data?.str("container_extension"),
            tmdbId = info.str("tmdb_id") ?: info.str("tmdb"),
        )
        synchronized(movieCache) { movieCache[id] = m }
        return m
    }

    suspend fun seriesInfo(login: LoginEntity, id: Long): SeriesInfo {
        synchronized(seriesCache) { seriesCache[id] }?.let { return it }
        val root = xtream.getObject(login, "get_series_info", mapOf("series_id" to id.toString()))
        val info = root["info"] as? JsonObject ?: JsonObject(emptyMap())
        val seasonNames = (root["seasons"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val n = o.int("season_number") ?: return@mapNotNull null
            n to (o.str("name") ?: "Season $n")
        }?.toMap().orEmpty()
        val episodes = ArrayList<Episode>()
        fun readEpisode(el: Any?, seasonHint: Int?) {
            val o = el as? JsonObject ?: return
            val epInfo = o["info"] as? JsonObject
            val season = o.int("season") ?: seasonHint ?: 1
            val number = o.int("episode_num") ?: (episodes.count { it.season == season } + 1)
            episodes += Episode(
                id = o.long("id") ?: return,
                season = season,
                number = number,
                title = episodeTitle(o.str("title"), number),
                plot = epInfo?.str("plot"),
                image = epInfo?.str("movie_image") ?: epInfo?.str("cover_big"),
                durationMs = epInfo?.let(::durationMs) ?: 0L,
                ext = o.str("container_extension") ?: "mp4",
            )
        }
        when (val eps = root["episodes"]) {
            is JsonObject -> eps.forEach { (k, v) -> (v as? JsonArray)?.forEach { readEpisode(it, k.toIntOrNull()) } }
            is JsonArray -> eps.forEach { s -> (s as? JsonArray)?.forEach { readEpisode(it, null) } ?: readEpisode(s, null) }
            else -> {}
        }
        val seasons = episodes.groupBy { it.season }.toSortedMap().map { (n, list) ->
            Season(n, seasonNames[n]?.takeIf { it.isNotBlank() } ?: "Season $n", list.sortedBy { it.number })
        }
        val p = VodNaming.parse(info.str("name") ?: "", info.str("releaseDate") ?: info.str("release_date"))
        val s = SeriesInfo(
            title = p.title,
            year = p.year,
            plot = info.str("plot"),
            genre = info.str("genre"),
            cast = info.str("cast"),
            rating = info.str("rating")?.toFloatOrNull()?.takeIf { it > 0 },
            backdrop = image(info["backdrop_path"]),
            poster = info.str("cover"),
            seasons = seasons,
            tmdbId = info.str("tmdb") ?: info.str("tmdb_id"),
        )
        synchronized(seriesCache) { seriesCache[id] = s }
        return s
    }

    /** IMDb chart in chart order, each title matched to the best copy in the catalogue. */
    suspend fun imdbMovies(loginId: Long, chart: ImdbCharts.Chart): List<Pair<MovieEntity, ImdbCharts.Title>> {
        val titles = imdb.chart(chart)
        if (titles.isEmpty()) return emptyList()
        val keys = titles.flatMap { listOfNotNull(VodNaming.key(it.title), it.originalTitle?.let(VodNaming::key)) }.distinct()
        val found = keys.chunked(400).flatMap { db.vod().moviesByKeys(loginId, it) }.groupBy { it.titleKey }
        val seen = HashSet<Long>()
        return titles.mapNotNull { t ->
            val list = (found[VodNaming.key(t.title)].orEmpty() + t.originalTitle?.let { found[VodNaming.key(it)] }.orEmpty())
            val best = pick(list, t.year, { it.year }, compareByDescending<MovieEntity> { it.quality }.thenByDescending { it.added })
            best?.takeIf { seen.add(it.streamId) }?.let { it to t }
        }
    }

    suspend fun imdbSeries(loginId: Long, chart: ImdbCharts.Chart): List<Pair<SeriesEntity, ImdbCharts.Title>> {
        val titles = imdb.chart(chart)
        if (titles.isEmpty()) return emptyList()
        val keys = titles.flatMap { listOfNotNull(VodNaming.key(it.title), it.originalTitle?.let(VodNaming::key)) }.distinct()
        val found = keys.chunked(400).flatMap { db.vod().seriesByKeys(loginId, it) }.groupBy { it.titleKey }
        val seen = HashSet<Long>()
        return titles.mapNotNull { t ->
            val list = (found[VodNaming.key(t.title)].orEmpty() + t.originalTitle?.let { found[VodNaming.key(it)] }.orEmpty())
            val best = pick(list, t.year, { it.year }, compareByDescending<SeriesEntity> { it.updated })
            best?.takeIf { seen.add(it.seriesId) }?.let { it to t }
        }
    }

    /** Same title, year within one (or unknown on our side), then the best copy. */
    private fun <T> pick(list: List<T>, year: Int?, yearOf: (T) -> Int?, order: Comparator<T>): T? {
        if (list.isEmpty()) return null
        val ok = if (year == null) list else {
            val exact = list.filter { yearOf(it)?.let { y -> abs(y - year) <= 1 } == true }
            exact.ifEmpty { list.filter { yearOf(it) == null } }
        }
        return ok.sortedWith(order).firstOrNull()
    }

    private fun durationMs(info: JsonObject): Long {
        info.long("duration_secs")?.takeIf { it > 0 }?.let { return it * 1000 }
        val d = info.str("duration") ?: return 0
        val parts = d.split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> d.filter(Char::isDigit).toLongOrNull()?.let { it * 60_000 } ?: 0
        }
    }

    private fun image(el: Any?): String? = when (el) {
        is JsonArray -> el.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.takeIf { u -> u.startsWith("http") } }
        is JsonPrimitive -> el.content.takeIf { it.startsWith("http") }
        else -> null
    }

    /** "Dark - S01E02 - Lies" → "Lies"; titles that only repeat the show and number become "Episode 2". */
    private fun episodeTitle(raw: String?, number: Int): String {
        val t = raw?.trim().orEmpty()
        val m = Regex("""(?i)S\d{1,2}\s*E\d{1,3}\s*[-–:]?\s*(.*)$""").find(t)
        val rest = m?.groupValues?.get(1)?.trim()?.trim('-', '–', ':', ' ').orEmpty()
        return when {
            m != null && rest.isNotEmpty() -> rest
            m == null && t.isNotEmpty() -> t
            else -> "Episode $number"
        }
    }

    private fun <K, V> lru(max: Int) = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
}
