package dev.k3be.restv.ui.vod

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import dev.k3be.restv.ui.components.ProgressBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.k3be.restv.Session
import dev.k3be.restv.SyncState
import dev.k3be.restv.ui.components.Toast
import dev.k3be.restv.ui.components.TvButton
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.live.SidebarItem
import dev.k3be.restv.ui.live.TopBar
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import kotlinx.coroutines.delay

/** Movies or Series: hero for the focused title, then horizontal rails. */
@Composable
fun VodHomeScreen(
    session: Session,
    kind: String,
    sync: SyncState,
    onDownload: () -> Unit,
    onOpen: (VodCard) -> Unit,
    onSeeAll: (Rail) -> Unit,
    onNavigate: (SidebarItem) -> Unit,
) {
    val vm = rememberVodViewModel(session, kind)
    val count by vm.count.collectAsStateWithLifecycle()
    val rails by vm.rails.collectAsStateWithLifecycle()
    val wishIds by vm.wishIds.collectAsStateWithLifecycle()
    val tab = if (kind == "movie") SidebarItem.Movies else SidebarItem.Series
    var toast by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<Pair<Rail, VodCard>?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2_500); toast = null } }

    // Reload once a download finishes.
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(sync) {
        if (sync is SyncState.Running) wasRunning = true
        else if (wasRunning) { wasRunning = false; vm.reload() }
    }
    // First visit with nothing downloaded: start the download.
    LaunchedEffect(count) { if (count == 0 && sync !is SyncState.Running) onDownload() }

    val firstFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        VodHeroBackdrop(vm)
        Column(Modifier.fillMaxSize()) {
            TopBar(tab, onNavigate, autoHide = count != null && count!! > 0)
            when {
                count == null -> {}
                count == 0 -> FirstDownload(kind, sync, firstFocus, onDownload)
                else -> {
                    Box(Modifier.fillMaxWidth().weight(1f)) { VodHeroInfo(vm, Modifier.align(Alignment.BottomStart)) }
                    OneRailAtATime(
                        rails = rails,
                        wishIds = wishIds,
                        restore = vm.lastFocus,
                        onRailShown = vm::loadRail,
                        onFocus = { rail, c -> vm.focus(c); vm.lastFocus = "${rail.id}|${c.id}" },
                        onClick = onOpen,
                        onLongClick = { rail, c -> menu = rail to c },
                        onSeeAll = { rail -> if ((rail.cards?.size ?: 0) >= 8 || rail.id.startsWith("cat:")) ({ onSeeAll(rail) }) else null },
                    )
                }
            }
        }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = S.safeY)) }
    }
    // Hold OK on a poster: details, My List, and removing it from Recently watched.
    menu?.let { (rail, c) ->
        val inList = c.id in wishIds
        dev.k3be.restv.ui.components.ActionMenu(
            title = c.title,
            actions = buildList {
                add("Open details" to { onOpen(c) })
                add((if (inList) "Remove from My List" else "Add to My List") to {
                    toast = if (inList) "Removed from My List" else "Added to My List"
                    vm.toggleWish(c)
                })
                if (rail.id == "recent") add("Remove from Recently watched" to { vm.forget(c); toast = "Removed from Recently watched" })
            },
            onClose = { menu = null },
        )
    }
}

@Composable
private fun VodHeroBackdrop(vm: VodViewModel) {
    val hero by vm.hero.collectAsStateWithLifecycle()
    // Wait for focus to settle before swapping the big picture: fast scrolling stays smooth.
    var shown by remember { mutableStateOf<String?>(null) }
    val target = hero?.backdrop ?: hero?.card?.poster?.takeIf { vm.kind == "series" }
    LaunchedEffect(target) { delay(150); shown = target }
    HeroBackdrop(shown)
}

@Composable
private fun VodHeroInfo(vm: VodViewModel, modifier: Modifier) {
    val hero by vm.hero.collectAsStateWithLifecycle()
    HeroInfo(hero, modifier)
}

/**
 * Rails stacked vertically in a viewport exactly one rail tall: Up/Down slide to the next rail,
 * so only one row of posters is ever on screen under the hero.
 */
@Composable
fun OneRailAtATime(
    rails: List<Rail>,
    wishIds: Set<Long>,
    restore: String?,
    onRailShown: (String) -> Unit,
    onFocus: (Rail, VodCard) -> Unit,
    onClick: (VodCard) -> Unit,
    onLongClick: (Rail, VodCard) -> Unit,
    onSeeAll: (Rail) -> (() -> Unit)?,
) {
    val listState = rememberLazyListState()
    val start = remember { restore }
    var restored by remember { mutableStateOf(false) }
    BrowseRows(count = rails.size, key = { rails[it].id }, state = listState) { i ->
            val rail = rails[i]
            LaunchedEffect(rail.id) { onRailShown(rail.id) }
            val restoreId = start?.takeIf { it.startsWith("${rail.id}|") }?.substringAfter('|')?.toLongOrNull()
            val firstRail = start == null && i == 0
            RailRow(
                rail = rail,
                wishIds = wishIds,
                restoreCardId = if (restored) null else restoreId ?: if (firstRail) rail.cards?.firstOrNull()?.id else null,
                onFocus = { c -> restored = true; onFocus(rail, c) },
                onClick = onClick,
                onLongClick = { c -> onLongClick(rail, c) },
                onSeeAll = onSeeAll(rail),
            )
    }
}

/** Every title behind a rail, as a grid. */
@Composable
fun VodGridScreen(
    session: Session,
    kind: String,
    railId: String,
    title: String,
    onOpen: (VodCard) -> Unit,
) {
    val vm = rememberVodViewModel(session, kind)
    val wishIds by vm.wishIds.collectAsStateWithLifecycle()
    var cards by remember { mutableStateOf<List<VodCard>?>(null) }
    val startId = remember { vm.gridFocus[railId] }
    var focusedAny by remember { mutableStateOf(false) }
    LaunchedEffect(railId) { cards = vm.all(railId) }
    val state = rememberLazyGridState()
    LaunchedEffect(cards) {
        val l = cards ?: return@LaunchedEffect
        val i = l.indexOfFirst { it.id == startId }
        if (i > 0) state.scrollToItem(i)
    }
    Column(Modifier.fillMaxSize().padding(top = S.safeY)) {
        Txt(title, T.titleLg, modifier = Modifier.padding(start = S.safeX))
        val list = cards
        Txt(if (list == null) "Loading…" else "${list.size} titles", T.caption, color = C.inkFaint, modifier = Modifier.padding(start = S.safeX, bottom = S.s3))
        if (list != null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(POSTER_W + 14.dp),
                state = state,
                contentPadding = PaddingValues(start = S.safeX, end = S.safeX, top = 8.dp, bottom = S.s8),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list.size, key = { list[it].id }) { i ->
                    val c = list[i]
                    PosterCard(
                        card = c,
                        inList = c.id in wishIds,
                        showTitle = true,
                        requestFocus = !focusedAny && (startId ?: list.first().id) == c.id,
                        onFocus = { focusedAny = true; vm.gridFocus[railId] = c.id },
                        onClick = { onOpen(c) },
                        onLongClick = { vm.toggleWish(c) },
                    )
                }
            }
        }
    }
}

/** First visit: the catalogue downloads once, with a real progress bar. */
@Composable
private fun FirstDownload(kind: String, sync: SyncState, focus: FocusRequester, onDownload: () -> Unit) {
    val label = if (kind == "movie") "movies" else "series"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(S.radiusLg)).background(C.bg100).padding(S.s6),
            verticalArrangement = Arrangement.spacedBy(S.s3),
        ) {
            Txt(if (kind == "movie") "Getting your movies ready" else "Getting your series ready", T.titleLg)
            when (sync) {
                is SyncState.Running -> {
                    Txt(sync.message, T.body, color = C.inkMuted)
                    val f = sync.fraction
                    if (f != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s3)) {
                            ProgressBar(f, Modifier.weight(1f), height = 8.dp)
                            Txt("${(f * 100).toInt()}%", T.label, color = C.accent)
                        }
                    } else {
                        IndeterminateBar(Modifier.fillMaxWidth())
                    }
                    Txt("This happens once. After that, Sync in Settings keeps it up to date.", T.caption, color = C.inkFaint, maxLines = 2)
                }
                is SyncState.Failed -> {
                    Txt(sync.message, T.body, color = C.danger, maxLines = 3)
                    TvButton("Try again", primary = true, modifier = Modifier.focusRequester(focus), onClick = onDownload)
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
                else -> {
                    Txt("Your $label haven't been downloaded yet.", T.body, color = C.inkMuted)
                    TvButton("Download $label", primary = true, modifier = Modifier.focusRequester(focus), onClick = onDownload)
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
            }
        }
    }
}

@Composable
fun IndeterminateBar(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "bar")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "x")
    BoxWithConstraints(modifier.height(8.dp).clip(RoundedCornerShape(50)).background(C.track)) {
        val w = maxWidth * 0.3f
        Box(Modifier.offset(x = (maxWidth + w) * x - w).width(w).fillMaxHeight().background(C.accent, RoundedCornerShape(50)))
    }
}
