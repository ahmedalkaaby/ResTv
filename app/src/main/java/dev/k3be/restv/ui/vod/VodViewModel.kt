package dev.k3be.restv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.data.ImdbCharts
import dev.k3be.restv.data.VodNaming
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.data.db.MovieEntity
import dev.k3be.restv.data.db.SeriesEntity
import dev.k3be.restv.data.db.VodCategoryEntity
import dev.k3be.restv.data.db.WatchEntity
import dev.k3be.restv.data.db.WishEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A poster in a rail. [kind] is "movie" or "series". */
data class VodCard(
    val kind: String,
    val id: Long,
    val title: String,
    val year: Int?,
    val poster: String?,
    val rating: Float? = null,
    val quality: Int = 0,
    /** Where you stopped, 0..1. */
    val progress: Float? = null,
    /** Small label over the poster: "S2:E5", "#3". */
    val note: String? = null,
    val backdrop: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    /** Provider source/language tag: "EN", "AR", "AR SUBS". */
    val source: String? = null,
    /** Other sources of the same title, shown as "+2". */
    val otherSources: List<String> = emptyList(),
)

data class Rail(val id: String, val title: String, val cards: List<VodCard>?, val accent: Boolean = false)

data class Hero(val card: VodCard, val backdrop: String?, val plot: String?, val meta: String)

@Composable
fun rememberVodViewModel(session: Session, kind: String): VodViewModel {
    val app = LocalContext.current.applicationContext as RestvApp
    return viewModel(
        key = "vod-$kind-${session.login.id}",
        factory = viewModelFactory { initializer { VodViewModel(app, session.account.id, session.login, kind) } },
    )
}

/** Home of Movies or Series: rails in the user's order, then the provider's categories. */
class VodViewModel(
    private val app: RestvApp,
    private val accountId: Long,
    private val login: LoginEntity,
    val kind: String,
) : ViewModel() {
    private val loginId = login.id
    private val isMovie = kind == "movie"

    private val _count = MutableStateFlow<Int?>(null)
    val count: StateFlow<Int?> = _count

    private val fixed = MutableStateFlow<List<Rail>>(emptyList())
    private val categoryRails = MutableStateFlow<List<Rail>>(emptyList())
    private val categoryCards = MutableStateFlow<Map<String, List<VodCard>>>(emptyMap())

    val wish: StateFlow<List<WishEntity>> = app.db.wish().observe(accountId, loginId)
        .map { list -> list.filter { it.kind == kind } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val wishIds: StateFlow<Set<Long>> = wish.map { l -> l.map { it.itemId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val recentWatch: StateFlow<List<WatchEntity>> = app.db.watch().observeRecent(accountId, loginId)
        .map { list -> list.filter { if (isMovie) it.kind == "movie" else it.kind == "episode" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val personal = MutableStateFlow<List<Rail>>(emptyList())

    val rails: StateFlow<List<Rail>> = combine(personal, fixed, categoryRails, categoryCards) { p, f, c, cards ->
        p + f + c.map { r -> r.copy(cards = cards[r.id]) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _hero = MutableStateFlow<Hero?>(null)
    val hero: StateFlow<Hero?> = _hero

    /** "railId|cardId" of the last focused poster, to come back to it. */
    var lastFocus: String? = null
    val gridFocus = HashMap<String, Long>()

    init {
        reload()
        viewModelScope.launch { recentWatch.collect { refreshPersonal() } }
        viewModelScope.launch { wish.collect { refreshPersonal() } }
    }

    fun reload() {
        viewModelScope.launch {
            val n = if (isMovie) app.db.vod().countMovies(loginId) else app.db.vod().countSeries(loginId)
            _count.value = n
            if (n == 0) return@launch
            catNames = app.db.vod().categories(loginId, kind).associate { it.categoryId to it.rawName }
            refreshPersonal()
            loadCategories()
            loadFixed()
        }
    }

    private suspend fun refreshPersonal() {
        val out = ArrayList<Rail>()
        val recent = recentCards()
        if (recent.isNotEmpty()) out += Rail("recent", "Recently watched", recent, accent = true)
        val wishCards = cardsFor(wish.value.map { it.itemId })
        if (wishCards.isNotEmpty()) out += Rail("wish", "My List", wishCards)
        personal.value = out
    }

    private suspend fun recentCards(): List<VodCard> {
        val watch = recentWatch.value
        if (isMovie) {
            val byId = app.db.vod().moviesByIds(loginId, watch.map { it.itemId }.take(40)).associateBy { it.streamId }
            return watch.take(40).mapNotNull { w ->
                val m = byId[w.itemId] ?: return@mapNotNull null
                m.card().copy(progress = w.fraction, note = if (w.finished) "Watched" else left(w))
            }
        }
        val latest = watch.groupBy { it.seriesId }.values.map { it.maxBy { w -> w.updatedAt } }.sortedByDescending { it.updatedAt }.take(40)
        val byId = app.db.vod().seriesByIds(loginId, latest.map { it.seriesId }).associateBy { it.seriesId }
        return latest.mapNotNull { w ->
            val s = byId[w.seriesId] ?: return@mapNotNull null
            s.card().copy(progress = w.fraction, note = "S${w.season}:E${w.episode}" + if (w.finished) " ✓" else "")
        }
    }

    private suspend fun cardsFor(ids: List<Long>): List<VodCard> {
        if (ids.isEmpty()) return emptyList()
        return if (isMovie) {
            val m = app.db.vod().moviesByIds(loginId, ids).associateBy { it.streamId }
            ids.mapNotNull { m[it]?.card() }
        } else {
            val m = app.db.vod().seriesByIds(loginId, ids).associateBy { it.seriesId }
            ids.mapNotNull { m[it]?.card() }
        }
    }

    /** Trending, IMDb Top 250, the five platforms, recently added. Each appears when it has titles. */
    private fun loadFixed() {
        viewModelScope.launch {
            val trending = async { imdbRail("trending", "Trending now", if (isMovie) ImdbCharts.Chart.POPULAR else ImdbCharts.Chart.POPULAR_TV, 60) }
            val top = async { imdbRail("top", "IMDb Top 250", if (isMovie) ImdbCharts.Chart.TOP else ImdbCharts.Chart.TOP_TV, 250) }
            // One row of platform tiles; each opens that platform's own page.
            val available = VodNaming.platforms.filter { p ->
                (if (isMovie) app.db.vod().moviesOnPlatform(loginId, p, 1) else app.db.vod().seriesOnPlatform(loginId, p, 1)).isNotEmpty()
            }
            val platforms = if (available.isEmpty()) emptyList() else listOf(
                Rail("platforms", "Streaming platforms", available.mapIndexed { i, p -> VodCard("platform", -(i + 1).toLong(), p, null, null) })
            )
            val latest = Rail("latest", "Recently added", dedupe(
                if (isMovie) app.db.vod().latestMovies(loginId, 80).map { it.card() } else app.db.vod().latestSeries(loginId, 80).map { it.card() }
            ).take(40))
            // Show the local rails right away; IMDb ones join when they arrive.
            fixed.value = platforms + latest
            val t = trending.await()
            val tp = top.await()
            fixed.value = platforms + listOfNotNull(t, tp) + latest
        }
    }

    private suspend fun imdbRail(id: String, title: String, chart: ImdbCharts.Chart, limit: Int): Rail? {
        val cards = runCatching {
            if (isMovie) app.vod.imdbMovies(loginId, chart).map { (m, t) -> m.card().copy(rating = t.rating ?: m.rating, poster = m.poster ?: t.image) }
            else app.vod.imdbSeries(loginId, chart).map { (s, t) -> s.card().copy(rating = t.rating ?: s.rating, poster = s.poster ?: t.image) }
        }.getOrDefault(emptyList()).take(limit)
        if (cards.isEmpty()) return null
        val ranked = if (id == "top") cards else cards.mapIndexed { i, c -> c.copy(note = "#${i + 1}") }
        return Rail(id, title, ranked)
    }

    private suspend fun loadCategories() {
        val cats = app.db.vod().categories(loginId, kind)
        val counts = (if (isMovie) app.db.vod().movieCounts(loginId) else app.db.vod().seriesCounts(loginId)).associate { it.categoryId to it.n }
        val ordered = cats.filter { (counts[it.categoryId] ?: 0) > 0 }.sortedWith(
            compareBy<VodCategoryEntity> { c -> c.platform?.let { VodNaming.platforms.indexOf(it) } ?: Int.MAX_VALUE }.thenBy { it.providerOrder }
        )
        categoryRails.value = ordered.map { Rail("cat:${it.categoryId}", it.name, null) }
    }

    /** Category rails load when they scroll into view. */
    fun loadRail(railId: String) {
        if (!railId.startsWith("cat:") || railId in categoryCards.value) return
        categoryCards.value = categoryCards.value + (railId to emptyList())
        viewModelScope.launch {
            val id = railId.removePrefix("cat:")
            val cards = if (isMovie) app.db.vod().moviesIn(loginId, id, 40).map { it.card() } else app.db.vod().seriesIn(loginId, id, 40).map { it.card() }
            categoryCards.value = categoryCards.value + (railId to cards)
        }
    }

    /** A platform's titles: newest first, or best rated first. */
    suspend fun platformCards(platform: String, byRating: Boolean = false): List<VodCard> {
        val cards = dedupe(
            if (isMovie) app.db.vod().moviesOnPlatform(loginId, platform, 3000).map { it.card() }
            else app.db.vod().seriesOnPlatform(loginId, platform, 3000).map { it.card() }
        )
        return if (byRating) cards.filter { (it.rating ?: 0f) > 0f }.sortedByDescending { it.rating } else cards
    }

    /** Everything behind a rail, for "See all". */
    suspend fun all(railId: String): List<VodCard> = when {
        railId == "recent" -> recentCards()
        railId == "wish" -> cardsFor(wish.value.map { it.itemId })
        railId == "trending" || railId == "top" -> rails.value.firstOrNull { it.id == railId }?.cards.orEmpty()
        railId == "latest" -> dedupe(if (isMovie) app.db.vod().latestMovies(loginId, 400).map { it.card() } else app.db.vod().latestSeries(loginId, 400).map { it.card() })
        railId.startsWith("platform:") -> railId.removePrefix("platform:").let { p ->
            dedupe(if (isMovie) app.db.vod().moviesOnPlatform(loginId, p, 2000).map { it.card() } else app.db.vod().seriesOnPlatform(loginId, p, 2000).map { it.card() })
        }
        railId.startsWith("cat:") -> railId.removePrefix("cat:").let { id ->
            if (isMovie) app.db.vod().moviesIn(loginId, id, 5000).map { it.card() } else app.db.vod().seriesIn(loginId, id, 5000).map { it.card() }
        }
        else -> emptyList()
    }

    /** One poster per title: the same film often sits in several categories and qualities. */
    private fun dedupe(cards: List<VodCard>): List<VodCard> {
        val groups = cards.groupBy { VodNaming.key(it.title) + "|" + (it.year ?: "") }
        val best = groups.values.associate { g ->
            val b = g.maxBy { it.quality }
            b.id to b.copy(otherSources = g.filter { it.id != b.id }.mapNotNull { it.source }.distinct().filter { it != b.source })
        }
        return cards.mapNotNull { best[it.id] }
    }

    private var heroJob: Job? = null

    fun focus(card: VodCard) {
        heroJob?.cancel()
        if (card.kind == "platform") {
            _hero.value = Hero(card, null, "Everything from ${card.title} in your subscription: movies and series in one place.", "Platform")
            return
        }
        _hero.value = Hero(card, card.backdrop ?: _hero.value?.takeIf { it.card.id == card.id }?.backdrop, card.plot, meta(card, null))
        heroJob = viewModelScope.launch {
            delay(300)
            if (card.kind == "movie") {
                val info = runCatching { app.vod.movieInfo(login, card.id) }.getOrNull() ?: return@launch
                _hero.value = Hero(card, info.backdrop ?: info.poster, info.plot, meta(card, listOfNotNull(
                    info.durationMs.takeIf { it > 0 }?.let(::duration), info.genre?.split(',')?.take(2)?.joinToString(", ") { it.trim() }
                )))
            } else if (card.backdrop == null || card.plot == null) {
                val info = runCatching { app.vod.seriesInfo(login, card.id) }.getOrNull() ?: return@launch
                _hero.value = Hero(card, info.backdrop ?: card.backdrop, info.plot ?: card.plot, meta(card, listOfNotNull(
                    "${info.seasons.size} season${if (info.seasons.size == 1) "" else "s"}", info.genre?.split(',')?.take(2)?.joinToString(", ") { it.trim() }
                )))
            }
        }
    }

    private fun meta(c: VodCard, extra: List<String>?): String =
        (listOfNotNull(c.year?.toString(), c.rating?.let { "★ %.1f".format(it) }) + (extra ?: listOfNotNull(c.genre))).joinToString("  ·  ")

    /** Removes a movie, or a whole series, from Recently watched / Continue watching. */
    fun forget(card: VodCard) {
        viewModelScope.launch {
            if (card.kind == "movie") app.db.watchRemove().removeMovie(accountId, loginId, card.id)
            else app.db.watchRemove().removeSeries(accountId, loginId, card.id)
        }
    }

    fun toggleWish(card: VodCard) {
        viewModelScope.launch {
            if (card.id in wishIds.value) app.db.wish().remove(accountId, loginId, card.kind, card.id)
            else app.db.wish().add(WishEntity(accountId, loginId, card.kind, card.id, System.currentTimeMillis()))
        }
    }

    /** Category id → provider name, for titles whose source tag is only on the category ("AR SUBS | Netflix"). */
    private var catNames: Map<String, String> = emptyMap()

    private fun MovieEntity.card() = VodCard("movie", streamId, name, year, poster, rating, quality,
        source = VodNaming.source(rawName, catNames[categoryId]))
    private fun SeriesEntity.card() = VodCard("series", seriesId, name, year, poster, rating, backdrop = backdrop, plot = plot, genre = genre,
        source = VodNaming.source(rawName, catNames[categoryId]))
}

fun duration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

fun left(w: WatchEntity): String? =
    if (w.durationMs > 0) "${duration(w.durationMs - w.positionMs)} left" else null
