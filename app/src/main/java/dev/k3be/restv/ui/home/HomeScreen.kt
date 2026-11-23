package dev.k3be.restv.ui.home

import dev.k3be.restv.ui.components.cardFocus
import androidx.compose.ui.graphics.Brush

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.k3be.restv.PlayItem
import dev.k3be.restv.Session
import dev.k3be.restv.data.Naming
import dev.k3be.restv.ui.components.Badge
import dev.k3be.restv.ui.components.BadgeKind
import dev.k3be.restv.ui.components.Toast
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.focusGlow
import dev.k3be.restv.ui.components.glass
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.live.ChannelRow
import dev.k3be.restv.ui.live.SidebarItem
import dev.k3be.restv.ui.live.TopBar
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import dev.k3be.restv.ui.vod.HeroBackdrop
import dev.k3be.restv.ui.vod.HeroInfo
import dev.k3be.restv.ui.vod.RAIL_H
import dev.k3be.restv.ui.vod.Rail
import dev.k3be.restv.ui.vod.RailHeader
import dev.k3be.restv.ui.vod.RailRow
import dev.k3be.restv.ui.vod.VodCard
import dev.k3be.restv.ui.vod.rememberVodViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One row on Home: posters, or live channels. */
private sealed interface HomeRow {
    val id: String
    val title: String
    data class Vod(val rail: Rail) : HomeRow { override val id get() = rail.id; override val title get() = rail.title }
    data class Live(override val id: String, override val title: String, val rows: List<ChannelRow>) : HomeRow
    data class Matches(override val id: String, override val title: String, val days: List<MatchDay>) : HomeRow
}

/** Where the app opens: continue watching across movies, series and live, My List, and the IMDb picks. */
@Composable
fun HomeScreen(
    session: Session,
    onOpen: (VodCard) -> Unit,
    onPlayVod: (List<PlayItem>, Int) -> Unit,
    onPlayLive: (List<ChannelRow>, Int) -> Unit,
    onNavigate: (SidebarItem) -> Unit,
) {
    val home = rememberHomeViewModel(session)
    val movies = rememberVodViewModel(session, "movie")
    val series = rememberVodViewModel(session, "series")
    val cont by home.continueWatching.collectAsStateWithLifecycle()
    val wish by home.myList.collectAsStateWithLifecycle()
    val live by home.recentChannels.collectAsStateWithLifecycle()
    val extra by home.extra.collectAsStateWithLifecycle()
    val matchDay by home.matchDay.collectAsStateWithLifecycle()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as dev.k3be.restv.RestvApp
    val movieRails by movies.rails.collectAsStateWithLifecycle()
    val seriesRails by series.rails.collectAsStateWithLifecycle()
    val movieWish by movies.wishIds.collectAsStateWithLifecycle()
    val seriesWish by series.wishIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var toast by remember { mutableStateOf<String?>(null) }
    var vodMenu by remember { mutableStateOf<Pair<String, VodCard>?>(null) }
    var liveMenu by remember { mutableStateOf<ChannelRow?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2_500); toast = null } }

    // The carousel at the top: your team's match today, then trending titles. Moving through the
    // rows below never changes it or fetches anything, so scrolling stays smooth.
    val slides = remember(matchDay, movieRails, seriesRails) { dev.k3be.restv.ui.vod.featuredSlides(movieRails, seriesRails, matchDay) }
    var slideIndex by rememberSaveable { mutableStateOf(0) }
    val heroButton = remember { androidx.compose.ui.focus.FocusRequester() }

    fun pick(list: List<Rail>, id: String) = list.firstOrNull { it.id == id }?.cards.orEmpty()
    val rows: List<HomeRow> = remember(matchDay, cont, live, wish, movieRails, seriesRails, extra) { buildList {
        if (matchDay.isNotEmpty()) add(HomeRow.Matches("matchday", if (matchDay.size == 1) "Your team plays today" else "Your teams play today", matchDay))
        if (cont.isNotEmpty()) add(HomeRow.Vod(Rail("continue", "Continue watching", cont, accent = true)))
        if (live.isNotEmpty()) add(HomeRow.Live("live", "Live TV · recently watched", live))
        // Platforms that have movies or series in the subscription, in the usual order.
        // Fixed: always there at once, no waiting for the library to load.
        val platformNames = dev.k3be.restv.data.VodNaming.platforms
        if (platformNames.isNotEmpty()) add(HomeRow.Vod(Rail("platforms", "Streaming platforms",
            platformNames.mapIndexed { i, n -> VodCard("platform", -(i + 1).toLong(), n, null, null) })))
        if (wish.isNotEmpty()) add(HomeRow.Vod(Rail("wish", "My List", wish)))
        pick(movieRails, "trending").takeIf { it.isNotEmpty() }?.let { add(HomeRow.Vod(Rail("trending-m", "Trending movies", it))) }
        pick(seriesRails, "trending").takeIf { it.isNotEmpty() }?.let { add(HomeRow.Vod(Rail("trending-s", "Trending series", it))) }
        pick(movieRails, "top").takeIf { it.isNotEmpty() }?.let { add(HomeRow.Vod(Rail("top-m", "IMDb Top 250 movies", it))) }
        pick(seriesRails, "top").takeIf { it.isNotEmpty() }?.let { add(HomeRow.Vod(Rail("top-s", "IMDb Top 250 series", it))) }
        pick(movieRails, "latest").takeIf { it.isNotEmpty() }?.let { add(HomeRow.Vod(Rail("latest-m", "New movies", it))) }
        pick(seriesRails, "latest").takeIf { it.isNotEmpty() }?.let { add(HomeRow.Vod(Rail("latest-s", "New episodes", it))) }
        extra.forEach { add(HomeRow.Vod(it)) }
    } }

    val start = remember { home.lastFocus }
    // First visit: the carousel's button has focus, as on Apple TV and Nuvio.
    LaunchedEffect(slides.isNotEmpty()) {
        if (start == null && slides.isNotEmpty()) { delay(60); runCatching { heroButton.requestFocus() } }
    }
    var restored by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun focusCard(rowId: String, c: VodCard) {
        restored = true
        home.lastFocus = "$rowId|${c.id}"
    }

    Box(Modifier.fillMaxSize()) {
        dev.k3be.restv.ui.vod.CarouselBackdrop(slides.getOrNull(slideIndex.coerceAtMost(slides.lastIndex.coerceAtLeast(0))))
        Column(Modifier.fillMaxSize()) {
            TopBar(SidebarItem.Home, onNavigate, autoHide = rows.isNotEmpty())
            Box(Modifier.fillMaxWidth().weight(1f)) {
                dev.k3be.restv.ui.vod.CarouselInfo(
                    slides = slides,
                    index = slideIndex,
                    onIndex = { slideIndex = it },
                    onOpen = { sl ->
                        sl.match?.let { app.matches.openMatchId = it.match.id; onNavigate(SidebarItem.Matches) }
                        sl.card?.let(onOpen)
                    },
                    buttonRequester = heroButton,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 10.dp),
                )
                if (rows.isEmpty()) {
                    Txt("Nothing here yet. Open Movies or Series once to download your library.", T.bodyLg, color = C.inkMuted,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = S.safeX))
                }
            }
            dev.k3be.restv.ui.vod.BrowseRows(count = rows.size, key = { rows[it].id }, state = listState) { i ->
                    val row = rows[i]
                    val next = rows.getOrNull(i + 1)?.title
                    val restoreKey = start?.takeIf { it.startsWith("${row.id}|") }?.substringAfter('|')
                    val restoreId = restoreKey?.toLongOrNull()
                    val first = start == null && i == 0 && slides.isEmpty()
                    when (row) {
                        is HomeRow.Vod -> RailRow(
                            rail = row.rail,
                            wishIds = movieWish + seriesWish,
                            restoreCardId = if (restored) null else restoreId ?: if (first) row.rail.cards?.firstOrNull()?.id else null,
                            onFocus = { c -> focusCard(row.id, c) },
                            onClick = { c ->
                                if (row.id == "continue") scope.launch {
                                    val r = home.resume(c)
                                    if (r != null) onPlayVod(r.first, r.second) else onOpen(c)
                                } else onOpen(c)
                            },
                            onLongClick = { c -> if (c.kind != "platform") vodMenu = row.id to c },
                            onSeeAll = null,
                            nextTitle = next,
                        )
                        is HomeRow.Matches -> MatchDayRail(
                            title = row.title,
                            days = row.days,
                            nextTitle = next,
                            restoreId = if (restored) null else restoreKey ?: if (first) row.days.firstOrNull()?.match?.id else null,
                            onFocus = { d -> restored = true; home.lastFocus = "${row.id}|${d.match.id}"; },
                            onClick = { d -> app.matches.openMatchId = d.match.id; onNavigate(SidebarItem.Matches) },
                        )
                        is HomeRow.Live -> LiveRail(
                            title = row.title,
                            rows = row.rows,
                            nextTitle = next,
                            restoreId = if (restored) null else restoreId ?: if (first) row.rows.firstOrNull()?.streamId else null,
                            onFocus = { r -> restored = true; home.lastFocus = "${row.id}|${r.streamId}"; },
                            onClick = { index -> onPlayLive(row.rows, index) },
                            onLongClick = { r -> liveMenu = r },
                        )
                    }
            }
        }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = S.safeY)) }
    }
    vodMenu?.let { (rowId, c) ->
        val vm = if (c.kind == "movie") movies else series
        val inList = c.id in movieWish + seriesWish
        dev.k3be.restv.ui.components.ActionMenu(
            title = c.title,
            actions = buildList {
                add("Open details" to { onOpen(c) })
                add((if (inList) "Remove from My List" else "Add to My List") to {
                    toast = if (inList) "Removed from My List" else "Added to My List"
                    vm.toggleWish(c)
                })
                if (rowId == "continue") add("Remove from Continue watching" to { vm.forget(c); toast = "Removed from Continue watching" })
            },
            onClose = { vodMenu = null },
        )
    }
    liveMenu?.let { r ->
        dev.k3be.restv.ui.components.ActionMenu(
            title = r.main.name,
            actions = listOf(
                "Watch" to { val i = live.indexOf(r); if (i >= 0) onPlayLive(live, i) },
                "Remove from recently watched" to { app.prefs.removeRecent(session.login.id, r.streamId); toast = "Removed from recently watched" },
            ),
            onClose = { liveMenu = null },
        )
    }
}

@Composable
private fun LiveRail(
    title: String,
    rows: List<ChannelRow>,
    nextTitle: String?,
    restoreId: Long?,
    onFocus: (ChannelRow) -> Unit,
    onClick: (Int) -> Unit,
    onLongClick: (ChannelRow) -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(RAIL_H), verticalArrangement = Arrangement.spacedBy(S.s2)) {
        RailHeader(title, false, nextTitle, S.safeX)
        dev.k3be.restv.ui.vod.KeepInView { LazyRow(
            contentPadding = PaddingValues(start = S.safeX, end = S.safeX, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(rows.size, key = { rows[it].streamId }) { i ->
                val r = rows[i]
                LiveTile(r, requestFocus = restoreId == r.streamId, onFocus = { onFocus(r) }, onClick = { onClick(i) }, onLongClick = { onLongClick(r) })
            }
        } }
    }
}

@Composable
private fun LiveTile(row: ChannelRow, requestFocus: Boolean, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fr = remember { FocusRequester() }
    if (focused) LaunchedEffect(Unit) { onFocus() }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val (scale, sheen) = dev.k3be.restv.ui.components.rememberCardFocus(focused)
    val shape = RoundedCornerShape(S.radiusMd)
    val ch = row.main
    Box(
        Modifier
            .size(240.dp, 135.dp)
            .cardFocus(focused, scale, shape, sheen)
            .background(Brush.linearGradient(listOf(C.bg300, C.bg100)))
            .focusRequester(fr)
            .remoteClickable(source, onClick, onLongClick),
    ) {
        if (!ch.icon.isNullOrBlank()) {
            AsyncImage(ch.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.align(Alignment.Center).width(140.dp).height(80.dp))
        } else {
            Txt(ch.name, T.title, color = C.inkMuted, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(12.dp))
        }
        Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Naming.qualityLabel(ch.quality)?.let { dev.k3be.restv.ui.vod.Chip(it, if (ch.quality == 3) C.uhd else C.ink) }
        }
        Txt(ch.name, T.caption, color = C.ink, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, C.bg000.copy(alpha = 0.8f)))).padding(horizontal = 10.dp, vertical = 5.dp))
    }
}
