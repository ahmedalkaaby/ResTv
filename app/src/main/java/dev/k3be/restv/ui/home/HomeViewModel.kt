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

    init {
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
}
