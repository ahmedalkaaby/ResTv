package dev.k3be.restv.ui.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import dev.k3be.restv.SyncState
import dev.k3be.restv.data.Naming
import dev.k3be.restv.data.NowNext
import dev.k3be.restv.ui.components.Badge
import dev.k3be.restv.ui.components.Icon
import dev.k3be.restv.ui.components.Icons
import dev.k3be.restv.ui.components.ProgressBar
import dev.k3be.restv.ui.components.QualityBadge
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val GRID_COLUMNS = 3

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/** The focused channel, big, above the grid: what's on, quality, category. */
@Composable
fun InfoBand(
    title: String,
    count: Int,
    preview: Preview?,
    categoryName: String?,
    sync: SyncState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().height(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(title, T.caption, color = C.inkFaint)
            Spacer(Modifier.width(8.dp))
            Txt("$count channels · 4K first", T.caption, color = C.inkFaint)
            Spacer(Modifier.weight(1f))
            when (sync) {
                is SyncState.Running -> Txt(sync.message, T.caption, color = C.accent)
                is SyncState.Failed -> Txt("Sync failed", T.caption, color = C.danger)
                SyncState.Idle -> {}
            }
        }
        val row = preview?.row
        if (row == null) {
            Txt(if (count == 0) "No channels here yet" else "Pick a channel", T.titleLg, color = C.inkMuted)
            return@Column
        }
        val ch = row.main
        Row(horizontalArrangement = Arrangement.spacedBy(S.s4), verticalAlignment = Alignment.Top) {
            ChannelLogo(ch, 76)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(ch.name, T.titleLg, modifier = Modifier.weight(1f, fill = false))
                    QualityBadge(ch.quality)
                    Naming.sourceTag(ch.rawName)?.let { Badge(it) }
                    if (ch.hasArchive) Badge("Catch-up")
                    row.others.forEach { o -> Naming.qualityLabel(o.quality)?.let { Badge("also $it") } }
                }
                categoryName?.let { Txt(it, T.caption, color = C.inkMuted) }
                NowLine(preview.nowNext)
            }
        }
    }
}

@Composable
private fun NowLine(nn: NowNext?) {
    val now = nn?.now
    if (now == null) {
        Txt(if (nn == null) "Loading guide…" else "No guide data", T.caption, color = C.inkFaint)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(now.title.ifBlank { "On now" }, T.label, modifier = Modifier.weight(1f, fill = false))
        Txt("${timeFmt.format(Date(now.start))} – ${timeFmt.format(Date(now.end))}", T.caption, color = C.inkMuted)
    }
    ProgressBar(now.progress(), Modifier.width(320.dp))
    nn.next?.let { Txt("Next ${timeFmt.format(Date(it.start))} · ${it.title}", T.caption, color = C.inkFaint) }
}

/** Channels as a grid of cards, like browsing movies. */
@Composable
fun ChannelGrid(
    channels: List<ChannelRow>,
    favorites: Set<Long>,
    nowTitles: Map<Long, String>,
    state: LazyGridState,
    focusTarget: Long?,
    onFocusTargetConsumed: () -> Unit,
    onFocus: (Int) -> Unit,
    onPlay: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onLeftEdge: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = GRID_COLUMNS,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = S.safeY, start = 6.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(S.s3),
        verticalArrangement = Arrangement.spacedBy(S.s3),
    ) {
        items(channels.size, key = { channels[it].streamId }) { index ->
            val row = channels[index]
            val fr = remember { FocusRequester() }
            if (focusTarget != null && (focusTarget == row.streamId || row.others.any { it.streamId == focusTarget })) {
                LaunchedEffect(focusTarget) { runCatching { fr.requestFocus() }; onFocusTargetConsumed() }
            }
            ChannelCard(
                row = row,
                favorite = row.streamId in favorites,
                nowTitle = nowTitles[row.streamId],
                modifier = Modifier
                    .focusRequester(fr)
                    .onPreviewKeyEvent { ev ->
                        // LEFT from the first column goes back to the category you came from.
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft && index % columns == 0) {
                            onLeftEdge(); true
                        } else false
                    },
                onFocus = { onFocus(index) },
                onClick = { onPlay(index) },
                onLongClick = { onLongPress(index) },
            )
        }
    }
}

@Composable
fun ChannelCard(
    row: ChannelRow,
    favorite: Boolean,
    nowTitle: String?,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ch = row.main
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    if (focused) LaunchedEffect(Unit) { onFocus() }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "card")
    val shape = RoundedCornerShape(S.radiusMd)
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(if (focused) C.bg300 else C.bg200)
            .then(if (focused) Modifier.border(3.dp, C.focus, shape) else Modifier)
            .remoteClickable(source, onClick, onLongClick),
    ) {
        // Logo, centred in the upper part of the card.
        Box(Modifier.fillMaxSize().padding(bottom = 30.dp), contentAlignment = Alignment.Center) {
            if (!ch.icon.isNullOrBlank()) {
                AsyncImage(
                    model = ch.icon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 96.dp, height = 52.dp),
                )
            } else {
                val initials = ch.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
                Txt(initials, T.titleLg, color = C.inkFaint)
            }
        }
        // Badges, top corners.
        Row(
            Modifier.align(Alignment.TopEnd).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Naming.sourceTag(ch.rawName)?.let { SmallTag(it, C.inkMuted, C.bg000.copy(alpha = 0.6f)) }
            when (ch.quality) {
                3 -> SmallTag("4K", C.uhd, C.bg000.copy(alpha = 0.6f))
                2 -> SmallTag("FHD", C.ink, C.bg000.copy(alpha = 0.6f))
                1 -> SmallTag("HD", C.inkMuted, C.bg000.copy(alpha = 0.6f))
            }
        }
        if (favorite) Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { Icon(Icons.star, C.accent, 14.dp) }
        // Name and what's on, bottom.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, C.bg000.copy(alpha = 0.85f))))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Txt(ch.name, T.label.copy(fontWeight = FontWeight.Bold), color = if (focused) C.ink else C.ink)
            Txt(nowTitle ?: (if (row.others.isNotEmpty()) "Also in ${row.others.mapNotNull { Naming.qualityLabel(it.quality) }.joinToString(", ")}" else ""), T.caption, color = C.inkMuted)
        }
    }
}

@Composable
private fun SmallTag(text: String, fg: Color, bg: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.dp)
    ) { Txt(text, T.caption.copy(fontWeight = FontWeight.Bold), color = fg) }
}
