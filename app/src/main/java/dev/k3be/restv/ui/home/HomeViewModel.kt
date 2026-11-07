package dev.k3be.restv.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.k3be.restv.PlayItem
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.data.VodNaming
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.data.db.WatchEntity
import dev.k3be.restv.ui.live.ChannelRow
import dev.k3be.restv.ui.vod.VodCard
import dev.k3be.restv.ui.vod.left
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun rememberHomeViewModel(session: Session): HomeViewModel {
    val app = LocalContext.current.applicationContext as RestvApp
    return viewModel(
        key = "home-${session.login.id}",
        factory = viewModelFactory { initializer { HomeViewModel(app, session.account.id, session.login) } },
    )
}

/** Home: what you were watching (movies, series, live), My List; the IMDb rails come from the VOD models. */
class HomeViewModel(
    private val app: RestvApp,
    private val accountId: Long,
    private val login: LoginEntity,
) : ViewModel() {
    private val loginId = login.id

    private val _continue = MutableStateFlow<List<VodCard>>(emptyList())
    val continueWatching: StateFlow<List<VodCard>> = _continue

    private val _wish = MutableStateFlow<List<VodCard>>(emptyList())
    val myList: StateFlow<List<VodCard>> = _wish

    private val _live = MutableStateFlow<List<ChannelRow>>(emptyList())
    val recentChannels: StateFlow<List<ChannelRow>> = _live

    /** Last progress row behind each continue-watching card, keyed "kind:id". */
    private var watchFor: Map<String, WatchEntity> = emptyMap()

    /** "railId|cardId" of the last focused tile. */
    var lastFocus: String? = null

    private val _extra = MutableStateFlow<List<dev.k3be.restv.ui.vod.Rail>>(emptyList())
    /** Variety rows: best rated, 4K, Arabic, kids, a few genres, and a daily mix. */
    val extra: StateFlow<List<dev.k3be.restv.ui.vod.Rail>> = _extra

    private val _matchDay = MutableStateFlow<List<MatchDay>>(emptyList())
    /** Today's matches of the teams picked in Settings, earliest first; finished ones drop off. */
    val matchDay: StateFlow<List<MatchDay>> = _matchDay

    private fun publish(all: List<dev.k3be.restv.data.Match>) {
        val now = System.currentTimeMillis()
        _matchDay.value = all.filter { app.matches.isFavorite(it) && !isOver(it, now) }
            .sortedBy { it.kickoff ?: Long.MAX_VALUE }
            .map { MatchDay(it, app.matches.logoFor(it, home = true), app.matches.logoFor(it, home = false)) }
    }

    init {
        loadExtras()
        viewModelScope.launch {
            app.prefs.favoriteTeams.collectLatest { favs ->
                if (favs.isEmpty()) { _matchDay.value = emptyList(); return@collectLatest }
                val date = java.time.LocalDate.now().toString()
                app.matches.cached(date)?.let { publish(it.matches) }
                runCatching { app.matches.fetch(date) }.getOrNull()?.let { publish(it.matches) }
                // Crests from each match page (national teams have none in the schedule); this also
                // gets the channels ready for when you press OK.
                for (d in _matchDay.value) {
                    if (d.homeLogo != null && d.awayLogo != null) continue
                    val c = runCatching { app.matches.coverage(loginId, d.match) }.getOrNull() ?: continue
                    _matchDay.value = _matchDay.value.map {
                        if (it.match.id == d.match.id) it.copy(homeLogo = c.homeLogo ?: it.homeLogo, awayLogo = c.awayLogo ?: it.awayLogo) else it
                    }
                }
            }
        }
        viewModelScope.launch {
            app.db.watch().observeRecent(accountId, loginId).collect { buildContinue(it) }
        }
        viewModelScope.launch {
            app.db.wish().observe(accountId, loginId).collect { list ->
                val movies = app.db.vod().moviesByIds(loginId, list.filter { it.kind == "movie" }.map { it.itemId }).associateBy { it.streamId }
                val shows = app.db.vod().seriesByIds(loginId, list.filter { it.kind == "series" }.map { it.itemId }).associateBy { it.seriesId }
                _wish.value = list.mapNotNull { w ->
                    if (w.kind == "movie") movies[w.itemId]?.let { VodCard("movie", it.streamId, it.name, it.year, it.poster, it.rating, it.quality, source = VodNaming.source(it.rawName)) }
                    else shows[w.itemId]?.let { VodCard("series", it.seriesId, it.name, it.year, it.poster, it.rating, backdrop = it.backdrop, plot = it.plot, genre = it.genre, source = VodNaming.source(it.rawName)) }
                }
            }
        }
        viewModelScope.launch {
            app.prefs.recent(loginId).collect { ids ->
                val found: Map<Long, dev.k3be.restv.data.db.ChannelEntity> =
                    if (ids.isEmpty()) emptyMap() else app.db.live().byIds(loginId, ids).associateBy { it.streamId }
                _live.value = ids.mapNotNull { found[it] }.map { ChannelRow(it, emptyList()) }
            }
        }
    }

    /** Unfinished movies and the episode you're on for each series, newest first. */
    private suspend fun buildContinue(watch: List<WatchEntity>) {
        val movies = watch.filter { it.kind == "movie" && !it.finished && it.positionMs > 30_000 }
        // Each show once: the episode you're in, or, if you finished it, the next one (shows you've
        // completed drop off).
        val next = HashMap<Long, dev.k3be.restv.data.Episode>()
        val episodes = watch.filter { it.kind == "episode" }.groupBy { it.seriesId }.values
            .map { it.maxBy { w -> w.updatedAt } }
            .sortedByDescending { it.updatedAt }.take(30)
            .filter { w ->
                if (!w.finished || w.durationMs == 0L) return@filter true
                val info = runCatching { app.vod.seriesInfo(login, w.seriesId) }.getOrNull() ?: return@filter false
                val all = info.seasons.flatMap { it.episodes }
                val k = all.indexOfFirst { it.id == w.itemId }
                val n = all.getOrNull(k + 1).takeIf { k >= 0 } ?: return@filter false
                next[w.seriesId] = n
                true
            }
        val items = (movies + episodes).sortedByDescending { it.updatedAt }.take(30)
        val m = app.db.vod().moviesByIds(loginId, items.filter { it.kind == "movie" }.map { it.itemId }).associateBy { it.streamId }
        val s = app.db.vod().seriesByIds(loginId, items.filter { it.kind == "episode" }.map { it.seriesId }).associateBy { it.seriesId }
        val map = HashMap<String, WatchEntity>()
        _continue.value = items.mapNotNull { w ->
            if (w.kind == "movie") {
                val e = m[w.itemId]
                map["movie:${w.itemId}"] = w
                VodCard("movie", w.itemId, e?.name ?: w.title, e?.year, e?.poster ?: w.image, e?.rating, e?.quality ?: 0,
                    progress = w.fraction, note = left(w), source = e?.let { VodNaming.source(it.rawName) })
            } else {
                val e = s[w.seriesId]
                map["series:${w.seriesId}"] = w
                val n = next[w.seriesId]
                VodCard("series", w.seriesId, e?.name ?: w.title, e?.year, e?.poster ?: w.image, e?.rating,
                    progress = if (n != null) null else w.fraction,
                    note = if (n != null) "Next · S${n.season}:E${n.number}" else "S${w.season}:E${w.episode}" + (left(w)?.let { " · $it" } ?: ""),
                    backdrop = e?.backdrop, plot = e?.plot, genre = e?.genre, source = e?.let { VodNaming.source(it.rawName) })
            }
        }
        watchFor = map
        nextFor = next
    }

    /** For shows whose current episode is finished: the episode that plays next. */
    private var nextFor: Map<Long, dev.k3be.restv.data.Episode> = emptyMap()

    /** Continue straight into playback: the movie, or the series from the episode you're on. */
    suspend fun resume(card: VodCard): Pair<List<PlayItem>, Int>? {
        val w = watchFor["${card.kind}:${card.id}"] ?: return null
        if (card.kind == "movie") {
            return listOf(PlayItem("movie", w.itemId, 0, 0, 0, w.title, w.subtitle, w.image, w.ext, w.durationMs)) to 0
        }
        val info = runCatching { app.vod.seriesInfo(login, card.id) }.getOrNull()
            ?: return listOf(PlayItem("episode", w.itemId, w.seriesId, w.season, w.episode, w.title, w.subtitle, w.image, w.ext, w.durationMs)) to 0
        val all = info.seasons.flatMap { it.episodes }
        val queue = all.map { e ->
            PlayItem("episode", e.id, card.id, e.season, e.number, info.title.ifBlank { card.title },
                "S${e.season}:E${e.number} · ${e.title}", e.image ?: info.backdrop ?: card.poster, e.ext, e.durationMs)
        }
        val target = nextFor[card.id]?.id ?: w.itemId
        val i = all.indexOfFirst { it.id == target }
        app.vod.lastPlayedSeries = card.id
        return if (i >= 0) queue to i else null
    }

    private fun dev.k3be.restv.data.db.MovieEntity.card() = VodCard("movie", streamId, name, year, poster, rating, quality, source = VodNaming.source(rawName))
    private fun dev.k3be.restv.data.db.SeriesEntity.card() = VodCard("series", seriesId, name, year, poster, rating,
        backdrop = backdrop, plot = plot, genre = genre, source = VodNaming.source(rawName))

    /** One card per title (the same film sits in several categories and qualities). */
    private fun dedupe(cards: List<VodCard>) = cards.groupBy { it.kind + VodNaming.key(it.title) + (it.year ?: "") }
        .values.map { g -> VodNaming.pickVersion(g, { it.source }, { it.quality }) }.let { best -> val ids = best.map { it.id }.toSet(); cards.filter { it.id in ids }.distinctBy { it.kind + it.id } }

    fun loadExtras() {
        viewModelScope.launch {
            val dao = app.db.vod()
            if (dao.countMovies(loginId) + dao.countSeries(loginId) == 0) return@launch
            val out = ArrayList<dev.k3be.restv.ui.vod.Rail>()
            val topM = dedupe(dao.topRatedMovies(loginId, 200).map { it.card() })
            val topS = dedupe(dao.topRatedSeries(loginId, 200).map { it.card() })
            if (topM.size >= 6) out += dev.k3be.restv.ui.vod.Rail("x-top-m", "Highest rated movies", topM.take(30))
            if (topS.size >= 6) out += dev.k3be.restv.ui.vod.Rail("x-top-s", "Highest rated series", topS.take(30))
            val uhd = dedupe(dao.movies4k(loginId, 120).map { it.card() })
            if (uhd.size >= 6) out += dev.k3be.restv.ui.vod.Rail("x-4k", "In 4K", uhd.take(30))

            // Rows built from the provider's own category names, in English or Arabic.
            val mCats = dao.categories(loginId, "movie")
            val sCats = dao.categories(loginId, "series")
            suspend fun fromCategories(id: String, title: String, words: List<String>) {
                val m = mCats.filter { c -> words.any { it in c.rawName.lowercase() } }
                val s = sCats.filter { c -> words.any { it in c.rawName.lowercase() } }
                val cards = m.take(4).flatMap { dao.moviesIn(loginId, it.categoryId, 25).map { e -> e.card() to e.added } } +
                    s.take(4).flatMap { dao.seriesIn(loginId, it.categoryId, 25).map { e -> e.card() to e.updated } }
                val list = dedupe(cards.sortedByDescending { it.second }.map { it.first })
                if (list.size >= 6) out += dev.k3be.restv.ui.vod.Rail(id, title, list.take(30))
            }
            fromCategories("x-ar", "Arabic picks", listOf("arab", "عرب", "ar |", "ar-", "ar:", "|ar|", "مسلسلات", "افلام"))
            fromCategories("x-kids", "Kids & family", listOf("kid", "child", "cartoon", "animation", "anime", "family", "اطفال", "أطفال", "كرتون"))
            fromCategories("x-action", "Action", listOf("action", "اكشن", "أكشن"))
            fromCategories("x-comedy", "Comedy", listOf("comedy", "كوميدي"))
            fromCategories("x-horror", "Horror & thriller", listOf("horror", "thriller", "رعب", "اثارة", "إثارة"))
            fromCategories("x-doc", "Documentaries", listOf("documentar", "وثائقي"))
            fromCategories("x-turkish", "Turkish series", listOf("turk", "تركي"))

            // A different mix every day from the best rated.
            val day = java.time.LocalDate.now().toEpochDay()
            val mix = (topM.take(80) + topS.take(80)).shuffled(java.util.Random(day)).take(24)
            if (mix.size >= 6) out += dev.k3be.restv.ui.vod.Rail("x-mix", "Today's mix", mix)
            _extra.value = out
        }
    }
}
