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

@Composable
fun LiveScreen(
    session: Session,
    sync: SyncState,
    onPlay: (List<ChannelRow>, Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = rememberLiveViewModel(session)
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val movingId by vm.movingId.collectAsStateWithLifecycle()
    val nowTitles by vm.nowTitles.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize()) {
        Sidebar(active = SidebarItem.Live, onSelect = { if (it == SidebarItem.Settings) onOpenSettings() })
        CategoriesPane(
            vm = vm,
            categories = categories,
            selected = selected,
            movingId = movingId,
            restoringChannel = vm.lastFocusedStreamId != null,
            modifier = Modifier.width(220.dp).fillMaxHeight().padding(top = S.safeY, start = 8.dp),
        )
        ChannelsPane(
            channels = channels,
            title = categories.firstOrNull { it.id == selected }?.name ?: "",
            favorites = favs,
            nowTitles = nowTitles,
            restoreStreamId = vm.lastFocusedStreamId,
            sync = sync,
            onFocus = { index ->
                val row = channels[index]
                val near = (1..4).flatMap { listOf(index + it, index - it) }.mapNotNull { channels.getOrNull(it) }
                vm.focusChannel(row, near)
            },
            onPlay = { index -> onPlay(channels, index) },
            onToggleFavorite = vm::toggleFavorite,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(top = S.safeY, start = S.s3, end = S.s3),
        )
        PreviewPane(
            preview = preview,
            modifier = Modifier.width(256.dp).fillMaxHeight().padding(top = S.safeY, end = S.safeX),
        )
    }
}

@Composable
private fun CategoriesPane(
    vm: LiveViewModel,
    categories: List<CatItem>,
    selected: String?,
    movingId: String?,
    restoringChannel: Boolean,
    modifier: Modifier,
) {
    var menuFor by remember { mutableStateOf<CatItem?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    var initialFocusDone by remember { mutableStateOf(false) }

    BackHandler(enabled = movingId != null) { vm.cancelMove() }
    BackHandler(enabled = menuFor != null) { menuFor = null }

    val movingIndex = categories.indexOfFirst { it.id == movingId }
    LaunchedEffect(movingIndex) { if (movingIndex >= 0) listState.animateScrollToItem((movingIndex - 3).coerceAtLeast(0)) }

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
            var prevGroup: String? = null
            var prevPinned = false
            categories.forEachIndexed { index, c ->
                val showHeader = movingId == null && !c.special && (
                    (c.pinned && !prevPinned) || (!c.pinned && (prevPinned || c.groupLabel != prevGroup))
                )
                if (showHeader) {
                    val label = if (c.pinned) "Pinned" else c.groupLabel
                    item(key = "h-$index-$label") { SectionLabel(label, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp)) }
                }
                if (!c.special) { prevGroup = c.groupLabel; prevPinned = c.pinned }
                item(key = c.id) {
                    val isMoving = c.id == movingId
                    val fr = remember { FocusRequester() }
                    if (c.id == selected && !initialFocusDone && !restoringChannel) {
                        LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; initialFocusDone = true }
                    }
                    FocusRow(
                        modifier = Modifier
                            .focusRequester(fr)
                            .then(if (isMoving) Modifier.offset(x = 8.dp) else Modifier)
                            .onPreviewKeyEvent { ev ->
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
                            else { vm.select(c.id); focusManager.moveFocus(FocusDirection.Right) }
                        },
                        onLongClick = if (c.special || movingId != null) null else ({ menuFor = c }),
                    ) { focused ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
private fun ChannelsPane(
    channels: List<ChannelRow>,
    title: String,
    favorites: Set<Long>,
    nowTitles: Map<Long, String>,
    restoreStreamId: Long?,
    sync: SyncState,
    onFocus: (Int) -> Unit,
    onPlay: (Int) -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    var restoreTarget by remember { mutableStateOf(restoreStreamId) }
    LaunchedEffect(channels) {
        val target = restoreTarget ?: return@LaunchedEffect
        val idx = channels.indexOfFirst { it.streamId == target || it.others.any { o -> o.streamId == target } }
        if (idx >= 0) listState.scrollToItem((idx - 2).coerceAtLeast(0))
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) {
            Txt(title, T.title, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Txt("${channels.size} channels · 4K first", T.caption, color = C.inkFaint)
        }
        when (sync) {
            is SyncState.Running -> Txt(sync.message, T.caption, color = C.accent, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            is SyncState.Failed -> Txt("Sync failed: ${sync.message}", T.caption, color = C.danger, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp), maxLines = 2)
            SyncState.Idle -> {}
        }
        if (channels.isEmpty()) {
            Txt(
                if (title == "Favourites") "Hold OK on any channel to add it to Favourites." else "No channels here yet.",
                T.body, color = C.inkMuted, modifier = Modifier.padding(12.dp), maxLines = 2,
            )
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(channels.size, key = { channels[it].streamId }) { index ->
                val row = channels[index]
                val fr = remember { FocusRequester() }
                if (restoreTarget != null && (restoreTarget == row.streamId || row.others.any { it.streamId == restoreTarget })) {
                    LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; restoreTarget = null }
                }
                FocusRow(
                    modifier = Modifier.focusRequester(fr),
                    height = 60.dp,
                    onFocused = { onFocus(index) },
                    onClick = { onPlay(index) },
                    onLongClick = { onToggleFavorite(row.main) },
                ) { _ -> ChannelRowContent(row, row.streamId in favorites, nowTitles[row.streamId]) }
            }
        }
    }
}

@Composable
private fun ChannelRowContent(row: ChannelRow, favorite: Boolean, nowTitle: String?) {
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

@Composable
private fun PreviewPane(preview: Preview?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (preview == null) {
            Txt("Pick a channel", T.body, color = C.inkFaint)
            return@Column
        }
        val ch = preview.row.main
        Box(
            Modifier
                .fillMaxWidth()
                .height(144.dp)
                .clip(RoundedCornerShape(S.radiusMd))
                .background(C.bg200),
            contentAlignment = Alignment.Center,
        ) { ChannelLogo(ch, 72) }
        Txt(ch.name, T.title, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QualityBadge(ch.quality)
            if (ch.hasArchive) Badge("Catch-up")
        }
        NowNextBlock(preview.nowNext)
        val others = preview.row.others
        if (others.isNotEmpty()) {
            SectionLabel("Also available in", Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                others.take(4).forEach { v ->
                    val label = dev.k3be.restv.data.Naming.qualityLabel(v.quality) ?: "SD"
                    Badge(label, if (v.quality == 3) BadgeKind.Uhd else BadgeKind.Outline)
                }
            }
        }
    }
}

@Composable
private fun NowNextBlock(nn: NowNext?) {
    val now = nn?.now
    if (now == null) {
        Txt(if (nn == null) "Loading guide…" else "No guide data for this channel", T.caption, color = C.inkFaint)
        return
    }
    Txt(now.title.ifBlank { "On now" }, T.bodyLg, maxLines = 2)
    Txt("${timeFmt.format(Date(now.start))} – ${timeFmt.format(Date(now.end))}", T.caption, color = C.inkMuted)
    ProgressBar(now.progress(), Modifier.fillMaxWidth())
    if (now.description.isNotBlank()) Txt(now.description, T.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = C.inkMuted, maxLines = 4)
    nn.next?.let { Txt("Next ${timeFmt.format(Date(it.start))} · ${it.title}", T.caption, color = C.inkFaint, maxLines = 2) }
}
