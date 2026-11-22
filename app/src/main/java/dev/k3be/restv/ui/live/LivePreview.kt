package dev.k3be.restv.ui.live

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.data.db.ChannelEntity
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.player.PlayStatus
import dev.k3be.restv.ui.player.StreamPlayer
import dev.k3be.restv.ui.player.StreamSource
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import kotlinx.coroutines.delay

/**
 * The small muted picture of the focused channel on the Live screen. Only one stream is ever open:
 * it starts once focus rests on a channel and is stopped before that channel (or any other) opens
 * full screen, so providers that allow a single connection are fine.
 */
class LivePreviewPlayer(val sp: StreamPlayer) {
    fun stop() {
        runCatching { sp.player.stop(); sp.player.clearMediaItems() }
    }
}

@Composable
fun rememberLivePreview(): LivePreviewPlayer {
    val app = LocalContext.current.applicationContext as RestvApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember {
        LivePreviewPlayer(StreamPlayer(context, app.streamHttp, app.prefs.agent.value.value, scope, onFormatChanged = { _, _ -> }).also {
            it.player.volume = 0f
            it.giveUpAfter = 2
        })
    }
    DisposableEffect(Unit) { onDispose { holder.stop(); holder.sp.release() } }
    return holder
}

@Composable
fun LivePreview(holder: LivePreviewPlayer, session: Session, channel: ChannelEntity?, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as RestvApp
    var started by remember { mutableStateOf(false) }
    val status by holder.sp.status.collectAsState()
    LaunchedEffect(channel?.streamId) {
        started = false
        holder.stop()
        val ch = channel ?: return@LaunchedEffect
        // Only once focus rests: skimming the grid never opens streams.
        delay(1_200)
        val format = app.db.prefs().channelFormat(session.login.id, ch.streamId) ?: "ts"
        holder.sp.play(
            StreamSource(
                key = ch.streamId,
                tsUrl = app.xtream.liveUrl(session.login, ch.streamId, "ts"),
                hlsUrl = app.xtream.liveUrl(session.login, ch.streamId, "m3u8"),
                format = format,
            )
        )
        started = true
    }
    val shape = RoundedCornerShape(S.radiusMd)
    Box(
        modifier.aspectRatio(16f / 9f).clip(shape).background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = holder.sp.player
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val playing = started && status is PlayStatus.Playing
        if (!playing) {
            Txt(
                when {
                    channel == null -> ""
                    !started -> "Preview"
                    status is PlayStatus.Failed -> "No preview"
                    else -> "Loading…"
                },
                T.caption, color = C.inkFaint,
            )
        } else {
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(C.live, CircleShape))
                Txt("  LIVE", T.caption, color = Color.White)
            }
        }
    }
}
