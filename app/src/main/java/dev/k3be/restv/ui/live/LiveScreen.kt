package dev.k3be.restv.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.SyncState
import dev.k3be.restv.data.NowNext
import dev.k3be.restv.data.db.ChannelEntity
import dev.k3be.restv.ui.components.Badge
import dev.k3be.restv.ui.components.BadgeKind
import dev.k3be.restv.ui.components.FocusRow
import dev.k3be.restv.ui.components.Icon
import dev.k3be.restv.ui.components.Icons
import dev.k3be.restv.ui.components.ProgressBar
import dev.k3be.restv.ui.components.QualityBadge
import dev.k3be.restv.ui.components.SectionLabel
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun rememberLiveViewModel(session: Session): LiveViewModel {
    val app = LocalContext.current.applicationContext as RestvApp
    return viewModel(
        key = "live-${session.login.id}",
        factory = viewModelFactory { initializer { LiveViewModel(app, session.account.id, session.login) } },
    )
}

/** What the categories list shows: group headers (collapsible) and categories. */
private sealed interface CatEntry {
    val key: String
    data class Header(val label: String, val count: Int, val open: Boolean, val collapsible: Boolean) : CatEntry {
        override val key get() = "h-$label"
    }
    data class Cat(val item: CatItem) : CatEntry {
        override val key get() = item.id
    }
}

private fun buildEntries(categories: List<CatItem>, openGroup: String?, selected: String?, moving: Boolean): List<CatEntry> {
    if (moving) return categories.map { CatEntry.Cat(it) }
    val out = ArrayList<CatEntry>()
    val specials = categories.filter { it.special }
    val pinned = categories.filter { !it.special && it.pinned }
    val rest = categories.filter { !it.special && !it.pinned }
    specials.forEach { out += CatEntry.Cat(it) }
    if (pinned.isNotEmpty()) {
        out += CatEntry.Header("Pinned", pinned.size, open = true, collapsible = false)
        pinned.forEach { out += CatEntry.Cat(it) }
    }
    // Groups in the order they first appear in the user's ordering.
    val groups = LinkedHashMap<String, MutableList<CatItem>>()
    rest.forEach { groups.getOrPut(it.groupLabel) { mutableListOf() } += it }
    groups.forEach { (label, items) ->
        val open = label == openGroup
        out += CatEntry.Header(label, items.size, open, collapsible = true)
        if (open) items.forEach { out += CatEntry.Cat(it) }
    }
    return out
}

@Composable
fun LiveScreen(
    session: Session,
    sync: SyncState,
    onPlay: (List<ChannelRow>, Int) -> Unit,
    onNavigate: (SidebarItem) -> Unit,
) {
    val vm = rememberLiveViewModel(session)
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val movingId by vm.movingId.collectAsStateWithLifecycle()
    val nowTitles by vm.nowTitles.collectAsStateWithLifecycle()
    val openGroup by vm.openGroup.collectAsStateWithLifecycle()

    val entries = remember(categories, openGroup, selected, movingId) {
        buildEntries(categories, openGroup, selected, movingId != null)
    }
    val catListState = rememberLazyListState(initialFirstVisibleItemIndex = vm.categoryScrollIndex)
    DisposableEffect(Unit) { onDispose { vm.categoryScrollIndex = catListState.firstVisibleItemIndex } }
    val selectedFr = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val categoryNames by vm.categoryNames.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val currentChannels by rememberUpdatedState(channels)
    /** Channel to focus once it is on screen: set on return from the player and by RIGHT from categories. */
    var channelFocusTarget by remember { mutableStateOf(vm.lastFocusedStreamId) }

    // Coming back from the player: scroll the grid to the last channel so it can take focus.
    LaunchedEffect(channels) {
        val target = channelFocusTarget ?: return@LaunchedEffect
        val idx = channels.indexOfFirst { it.streamId == target || it.others.any { o -> o.streamId == target } }
        if (idx >= 0) gridState.scrollToItem(idx)
    }

    /** RIGHT from the categories: back to the channel you were on, else the first one. */
    fun focusLastChannel() {
        scope.launch {
            delay(260) // let a just-selected category's channels load
            val list = currentChannels
            if (list.isEmpty()) return@launch
            val last = vm.lastFocusedStreamId
            val idx = list.indexOfFirst { it.streamId == last }.takeIf { it >= 0 } ?: 0
            gridState.scrollToItem(idx)
            withFrameNanos { }
            channelFocusTarget = list[idx].streamId
        }
    }

    /** Brings the selected category on screen and focuses it (used by LEFT from the channel list). */
    fun focusSelectedCategory() {
        scope.launch {
            val idx = entries.indexOfFirst { it is CatEntry.Cat && it.item.id == selected }
            if (idx < 0) return@launch
            val visible = catListState.layoutInfo.visibleItemsInfo.any { it.index == idx }
            if (!visible) catListState.scrollToItem((idx - 3).coerceAtLeast(0))
            withFrameNanos { }
            runCatching { selectedFr.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(active = SidebarItem.Live, onSelect = { if (it != SidebarItem.Live) onNavigate(it) })
        CategoriesPane(
            vm = vm,
            entries = entries,
            listState = catListState,
            selected = selected,
            selectedFr = selectedFr,
            movingId = movingId,
            restoringChannel = vm.lastFocusedStreamId != null,
            onInitialFocus = ::focusSelectedCategory,
            onRight = ::focusLastChannel,
            modifier = Modifier.width(220.dp).fillMaxHeight().padding(top = S.safeY, start = 8.dp),
        )
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = S.safeY, start = S.s4, end = S.safeX)) {
            InfoBand(
                title = categories.firstOrNull { it.id == selected }?.name ?: "",
                count = channels.size,
                preview = preview,
                categoryName = preview?.row?.main?.categoryId?.let { categoryNames[it] },
                sync = sync,
            )
            ChannelGrid(
                channels = channels,
                favorites = favs,
                nowTitles = nowTitles,
                state = gridState,
                focusTarget = channelFocusTarget,
                onFocusTargetConsumed = { channelFocusTarget = null },
                onFocus = { index ->
                    val row = channels[index]
                    val near = (1..6).flatMap { listOf(index + it, index - it) }.mapNotNull { channels.getOrNull(it) }
                    vm.focusChannel(row, near)
                },
                onPlay = { index -> onPlay(channels, index) },
                onLongPress = { index -> vm.toggleFavorite(channels[index].main) },
                onLeftEdge = ::focusSelectedCategory,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CategoriesPane(
    vm: LiveViewModel,
    entries: List<CatEntry>,
    listState: LazyListState,
    selected: String?,
    selectedFr: FocusRequester,
    movingId: String?,
    restoringChannel: Boolean,
    onInitialFocus: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier,
) {
    var menuFor by remember { mutableStateOf<CatItem?>(null) }
    val focusManager = LocalFocusManager.current
    var initialFocusDone by remember { mutableStateOf(false) }

    BackHandler(enabled = movingId != null) { vm.cancelMove() }
    BackHandler(enabled = menuFor != null) { menuFor = null }

    val movingIndex = entries.indexOfFirst { it is CatEntry.Cat && it.item.id == movingId }
    LaunchedEffect(movingIndex) { if (movingIndex >= 0) listState.animateScrollToItem((movingIndex - 3).coerceAtLeast(0)) }
    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isNotEmpty() && !initialFocusDone && !restoringChannel) { initialFocusDone = true; onInitialFocus() }
    }

    Column(modifier) {
        val menu = menuFor
        if (menu != null) {
            CategoryMenu(
                item = menu,
                onMove = { vm.startMove(menu.id); menuFor = null },
                onPin = { vm.togglePin(menu.id); menuFor = null },
                onHide = { vm.hide(menu.id); menuFor = null },
                onClose = { menuFor = null },
            )
            return@Column
        }
        if (movingId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(S.s3), modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                Txt("▲▼ Move", T.caption, color = C.inkMuted)
                Txt("OK Drop", T.caption, color = C.inkMuted)
                Txt("BACK Cancel", T.caption, color = C.inkMuted)
            }
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries.size, key = { entries[it].key }) { index ->
                when (val e = entries[index]) {
                    is CatEntry.Header -> GroupHeader(e) { vm.toggleGroup(e.label) }
                    is CatEntry.Cat -> {
                        val c = e.item
                        val isMoving = c.id == movingId
                        val own = remember { FocusRequester() }
                        val fr = if (c.id == selected) selectedFr else own
                        FocusRow(
                            modifier = Modifier
                                .focusRequester(fr)
                                .then(if (isMoving) Modifier.offset(x = 8.dp) else Modifier)
                                .onPreviewKeyEvent { ev ->
                                    if (!isMoving && movingId == null && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                                        onRight(); return@onPreviewKeyEvent true
                                    }
                                    if (!isMoving || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (ev.key) {
                                        Key.DirectionUp -> { vm.moveBy(-1); true }
                                        Key.DirectionDown -> { vm.moveBy(1); true }
                                        Key.DirectionLeft, Key.DirectionRight -> true
                                        else -> false
                                    }
                                },
                            selected = c.id == selected && movingId == null,
                            lifted = isMoving,
                            height = 40.dp,
                            onFocused = { vm.select(c.id) },
                            onClick = {
                                if (isMoving) vm.dropMove()
                                else { vm.select(c.id); onRight() }
                            },
                            onLongClick = if (c.special || movingId != null) null else ({ menuFor = c }),
                        ) { focused ->
                            Row(
                                Modifier.fillMaxWidth().padding(start = if (c.special || movingId != null) 12.dp else 20.dp, end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                val iconTint = if (isMoving || (c.id == selected && !focused)) C.accent else C.inkFaint
                                when {
                                    isMoving -> Icon(Icons.move, C.accent, 16.dp)
                                    c.id == FAV_ID -> Icon(Icons.star, iconTint, 16.dp)
                                    c.pinned -> Icon(Icons.pin, iconTint, 16.dp)
                                }
                                Txt(
                                    c.name,
                                    T.label,
                                    color = if (c.id == selected && !focused && movingId == null) C.accent else C.ink,
                                    modifier = Modifier.weight(1f),
                                )
                                Txt(c.count.toString(), T.caption, color = C.inkFaint)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(h: CatEntry.Header, onToggle: () -> Unit) {
    if (!h.collapsible) {
        SectionLabel(h.label, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
        return
    }
    FocusRow(height = 36.dp, onClick = onToggle) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (h.open) Icons.chevronDown else Icons.chevronRight, if (focused) C.ink else C.inkFaint, 14.dp)
            Txt(h.label.uppercase(), T.section.copy(color = if (focused || h.open) C.ink else C.inkMuted), modifier = Modifier.weight(1f))
            Txt(h.count.toString(), T.caption, color = C.inkFaint)
        }
    }
}

@Composable
private fun CategoryMenu(item: CatItem, onMove: () -> Unit, onPin: () -> Unit, onHide: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(item.id) { runCatching { first.requestFocus() } }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(S.radiusLg))
            .background(C.bg100)
            .padding(S.s3),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Txt(item.name, T.title, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
        Txt(item.groupLabel, T.caption, color = C.inkFaint, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        MenuOption("Move", Icons.move, onMove, Modifier.focusRequester(first))
        MenuOption(if (item.pinned) "Unpin" else "Pin to top", Icons.pin, onPin)
        MenuOption("Hide", Icons.eyeOff, onHide)
        MenuOption("Cancel", null, onClose)
    }
}

@Composable
private fun MenuOption(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusRow(modifier = modifier, onClick = onClick, height = 40.dp) { focused ->
        Row(
            Modifier
                .fillMaxSize()
                .background(if (focused) C.focus else C.bg100.copy(alpha = 0f))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val fg = if (focused) C.focusInk else C.ink
            if (icon != null) Icon(icon, fg, 16.dp)
            Txt(text, T.label, color = fg)
        }
    }
}

@Composable
internal fun ChannelRowContent(row: ChannelRow, favorite: Boolean, nowTitle: String?) {
    val ch = row.main
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.s3),
    ) {
        Txt(if (ch.num > 0) ch.num.toString() else "", T.caption, color = C.inkFaint, modifier = Modifier.width(28.dp))
        ChannelLogo(ch, 40)
        Column(Modifier.weight(1f)) {
            Txt(ch.name, T.label)
            if (!nowTitle.isNullOrBlank()) Txt(nowTitle, T.caption, color = C.inkMuted)
        }
        QualityBadge(ch.quality)
        if (row.others.isNotEmpty()) Txt("+${row.others.size}", T.caption, color = C.inkFaint)
        if (ch.hasArchive) Icon(Icons.history, C.inkFaint, 16.dp)
        if (favorite) Icon(Icons.star, C.accent, 16.dp)
    }
}

@Composable
fun ChannelLogo(ch: ChannelEntity, sizeDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(C.bg300),
        contentAlignment = Alignment.Center,
    ) {
        val initials = ch.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
        Txt(initials, T.caption, color = C.inkMuted)
        if (!ch.icon.isNullOrBlank()) {
            AsyncImage(
                model = ch.icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

