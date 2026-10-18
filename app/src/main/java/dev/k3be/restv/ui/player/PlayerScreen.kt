package dev.k3be.restv.ui.player

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.data.NowNext
import dev.k3be.restv.data.db.ChannelEntity
import dev.k3be.restv.data.db.ChannelPrefEntity
import dev.k3be.restv.ui.components.Badge
import dev.k3be.restv.ui.components.BadgeKind
import dev.k3be.restv.ui.components.Icon
import dev.k3be.restv.ui.components.Icons
import dev.k3be.restv.ui.components.ProgressBar
import dev.k3be.restv.ui.components.QualityBadge
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.live.ChannelLogo
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Aspect(val label: String, val mode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    session: Session,
    channels: List<ChannelEntity>,
    startIndex: Int,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as RestvApp
    val scope = rememberCoroutineScope()
    val agent = remember { app.prefs.agent.value }

    val sp = remember {
        StreamPlayer(context, app.streamHttp, agent.value, scope) { key, format ->
            scope.launch { app.db.prefs().setChannelFormat(ChannelPrefEntity(session.login.id, key, format)) }
        }
    }
    DisposableEffect(Unit) { onDispose { sp.release() } }

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    var overlayToken by remember { mutableIntStateOf(1) }
    var overlayVisible by remember { mutableStateOf(true) }
    var aspect by remember { mutableStateOf(Aspect.FIT) }
    var nowNext by remember { mutableStateOf<NowNext?>(null) }
    val status by sp.status.collectAsStateWithLifecycle()
    val video by sp.video.collectAsStateWithLifecycle()
    val channel = channels.getOrNull(index)

    LaunchedEffect(index) {
        val ch = channels.getOrNull(index) ?: return@LaunchedEffect
        val format = app.db.prefs().channelFormat(session.login.id, ch.streamId) ?: "ts"
        sp.play(
            StreamSource(
                key = ch.streamId,
                tsUrl = app.xtream.liveUrl(session.login, ch.streamId, "ts"),
                hlsUrl = app.xtream.liveUrl(session.login, ch.streamId, "m3u8"),
                format = format,
            )
        )
        nowNext = app.epg.peek(ch.streamId)
        nowNext = app.epg.nowNext(session.login, ch.streamId)
    }
    LaunchedEffect(overlayToken) {
        overlayVisible = true
        delay(5_000)
        overlayVisible = false
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        index = (index + delta + channels.size) % channels.size
        overlayToken++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp -> { zap(-1); true }
                    Key.DirectionDown, Key.ChannelDown -> { zap(1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (overlayVisible) overlayVisible = false else overlayToken++
                        true
                    }
                    Key.DirectionRight, Key.Menu -> {
                        aspect = Aspect.entries[(aspect.ordinal + 1) % Aspect.entries.size]
                        overlayToken++
                        true
                    }
                    Key.DirectionLeft -> { overlayToken++; true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = sp.player
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            },
            update = { it.resizeMode = aspect.mode },
            modifier = Modifier.fillMaxSize(),
        )

        StatusChip(status, Modifier.align(Alignment.TopEnd).padding(top = S.safeY, end = S.safeX))

        if (overlayVisible && channel != null) {
            InfoOverlay(
                channel = channel,
                position = "${index + 1}/${channels.size}",
                video = video,
                aspect = aspect,
                nowNext = nowNext,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun StatusChip(status: PlayStatus, modifier: Modifier) {
    val (text, color) = when (status) {
        is PlayStatus.Reconnecting -> "Reconnecting · try ${status.attempt} · ${status.reason}" to C.accent
        is PlayStatus.Failed -> status.message to C.danger
        PlayStatus.Buffering -> "Buffering…" to C.inkMuted
        PlayStatus.Starting -> "Opening channel…" to C.inkMuted
        PlayStatus.Playing -> return
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(C.bg100.copy(alpha = 0.92f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.sync, color, 16.dp)
        Txt(text, T.caption, color = C.ink)
    }
}

@Composable
private fun InfoOverlay(
    channel: ChannelEntity,
    position: String,
    video: VideoInfo?,
    aspect: Aspect,
    nowNext: NowNext?,
    modifier: Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(230.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, C.scrimStrong, C.scrimStrong)))
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = S.safeX, end = S.safeX, bottom = S.safeY),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s4)) {
                ChannelLogo(channel, 64)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(listOfNotNull(channel.num.takeIf { it > 0 }?.toString(), channel.name).joinToString(" · "), T.titleLg)
                        Badge("LIVE", BadgeKind.Live)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (video != null) {
                            when {
                                video.height >= 2000 -> Badge("4K", BadgeKind.Uhd)
                                video.height >= 1000 -> Badge("FHD")
                                video.height >= 700 -> Badge("HD")
                                else -> Badge("SD")
                            }
                            val fps = if (video.fps > 0) " · ${Math.round(video.fps)} fps" else ""
                            Badge(video.resolution + fps + (video.codec?.let { " · $it" } ?: ""))
                            video.audio?.let { Badge(it) }
                        } else {
                            QualityBadge(channel.quality)
                        }
                        Badge("Aspect: ${aspect.label}")
                    }
                }
                Spacer(Modifier.weight(1f))
                Txt(position, T.caption, color = C.inkFaint)
            }
            val now = nowNext?.now
            if (now != null) {
                Txt(now.title.ifBlank { "On now" }, T.bodyLg, color = C.inkMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s3)) {
                    Txt(timeFmt.format(Date(now.start)), T.caption, color = C.inkMuted)
                    ProgressBar(now.progress(), Modifier.weight(1f), height = 6.dp)
                    Txt(timeFmt.format(Date(now.end)), T.caption, color = C.inkMuted)
                }
                nowNext.next?.let { Txt("Next ${timeFmt.format(Date(it.start))} · ${it.title}", T.caption, color = C.inkFaint) }
            } else {
                Txt(channel.rawName, T.caption, color = C.inkFaint)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(S.s4)) {
                Hint("▲▼", "Change channel")
                Hint("▶", "Aspect")
                Hint("OK", "Hide info")
                Hint("BACK", "Channel list")
            }
        }
    }
}

@Composable
private fun Hint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.clip(RoundedCornerShape(S.radiusSm)).background(C.bg300).padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Txt(key, T.caption) }
        Txt(label, T.caption, color = C.inkMuted)
    }
}
