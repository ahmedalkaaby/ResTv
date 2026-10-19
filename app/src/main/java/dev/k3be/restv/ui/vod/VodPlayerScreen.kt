package dev.k3be.restv.ui.vod

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import dev.k3be.restv.PlayItem
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.data.db.WatchEntity
import dev.k3be.restv.ui.components.FocusRow
import dev.k3be.restv.ui.components.ProgressBar
import dev.k3be.restv.ui.components.Toast
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Fit(val label: String, val mode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FILL("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

/** One choice in the Audio & subtitles panel. */
private data class TrackChoice(val label: String, val selected: Boolean, val apply: () -> Unit)

/**
 * Movies and episodes: resumes where you stopped, saves progress as you watch, seeks with
 * Left/Right (faster when held), prefers Arabic subtitles, and plays the next episode at the end.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(
    session: Session,
    queue: List<PlayItem>,
    startIndex: Int,
    fromStart: Boolean,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as RestvApp
    val scope = rememberCoroutineScope()
    val accountId = session.account.id
    val loginId = session.login.id

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, queue.lastIndex)) }
    var startFresh by remember { mutableStateOf(fromStart) }
    val item = queue[index]

    val player = remember {
        val upstream = OkHttpDataSource.Factory(app.streamHttp).setUserAgent(app.prefs.agent.value.value)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, upstream)))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                // Arabic subtitles whenever the file has them; English as a second choice.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguages("ar", "ara", "en")
                    .setSelectUndeterminedTextLanguage(false)
                    .build()
                playWhenReady = true
            }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var overlayToken by remember { mutableIntStateOf(1) }
    var overlay by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(Fit.FIT) }
    var notice by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }

    fun save() {
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 && it != MC.TIME_UNSET } ?: item.durationMs
        if (pos < 5_000 && !ended) return
        val w = WatchEntity(
            accountId, loginId, item.kind, item.id, item.seriesId, item.season, item.episode,
            item.title, item.subtitle, item.image, item.ext,
            if (ended) dur else pos, dur, System.currentTimeMillis(),
        )
        scope.launch { app.db.watch().upsert(w) }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) { duration = player.duration.coerceAtLeast(0); error = null; retries = 0 }
                if (state == Player.STATE_ENDED) { ended = true; save() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) save()
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlayerError(e: PlaybackException) {
                // One quiet retry from the same spot covers most server hiccups.
                if (retries < 2) {
                    retries++
                    val at = player.currentPosition
                    player.prepare()
                    player.seekTo(at)
                    player.play()
                } else error = e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
                    .replaceFirstChar { it.uppercase() }
            }
        }
        player.addListener(listener)
        onDispose {
            save()
            player.removeListener(listener)
            player.release()
        }
    }

    // Load each item; resume where it stopped unless asked to start over.
    LaunchedEffect(index) {
        ended = false
        error = null
        retries = 0
        val url = app.xtream.vodUrl(session.login, if (item.kind == "movie") "movie" else "series", item.id, item.ext)
        val saved = app.db.watch().get(accountId, loginId, item.kind, item.id)
        val resumeAt = if (!startFresh && saved != null && !saved.finished && saved.positionMs > 30_000) saved.positionMs - 5_000 else 0L
        startFresh = false
        player.setMediaItem(MediaItem.fromUri(url), resumeAt)
        player.prepare()
        player.play()
        if (resumeAt > 0) notice = "Resuming from ${clock(resumeAt)}"
        overlayToken++
    }
    // Clock for the progress bar, plus a save every 15 s.
    LaunchedEffect(Unit) {
        var tick = 0
        while (isActive) {
            position = player.currentPosition
            if (player.duration > 0) duration = player.duration
            if (++tick % 30 == 0 && player.isPlaying) save()
            delay(500)
        }
    }
    LaunchedEffect(overlayToken) { overlay = true; delay(4_000); if (player.isPlaying) overlay = false }
    LaunchedEffect(notice) { if (notice != null) { delay(3_000); notice = null } }

    val hasNext = index < queue.lastIndex && queue[index + 1].kind == "episode"
    var countdown by remember { mutableIntStateOf(0) }
    LaunchedEffect(ended) {
        if (!ended || !hasNext) return@LaunchedEffect
        for (n in 10 downTo 1) { countdown = n; delay(1_000) }
        countdown = 0
        index++
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(panelOpen) { if (!panelOpen) runCatching { focus.requestFocus() } }

    BackHandler {
        when {
            panelOpen -> panelOpen = false
            else -> onExit()
        }
    }

    fun seek(deltaMs: Long) {
        val d = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, d - 1_000))
        position = player.currentPosition
        overlayToken++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (panelOpen || ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = ev.nativeKeyEvent.repeatCount
                val step = when { repeat < 3 -> 10_000L; repeat < 12 -> 30_000L; else -> 60_000L }
                when (ev.key) {
                    Key.DirectionLeft, Key.MediaRewind -> { seek(-step); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seek(step); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        if (repeat > 0) return@onKeyEvent true
                        if (error != null) { error = null; retries = 0; player.prepare(); player.play(); return@onKeyEvent true }
                        if (ended && hasNext) { index++; return@onKeyEvent true }
                        if (player.isPlaying) player.pause() else player.play()
                        overlayToken++
                        true
                    }
                    Key.MediaPlay -> { player.play(); true }
                    Key.MediaPause -> { player.pause(); overlayToken++; true }
                    Key.DirectionUp, Key.Menu -> { panelOpen = true; true }
                    Key.DirectionDown -> { overlayToken++; true }
                    Key.MediaNext -> { if (hasNext) index++; true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setStyle(CaptionStyleCompat(
                            android.graphics.Color.WHITE, 0x99000000.toInt(), android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null,
                        ))
                        setFractionalTextSize(0.058f)
                        setBottomPaddingFraction(0.08f)
                    }
                }
            },
            update = { it.resizeMode = fit.mode },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            Txt("Loading…", T.label, color = C.inkMuted, modifier = Modifier.align(Alignment.Center))
        }
        error?.let {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(S.s3)) {
                Txt("Couldn't play this: $it", T.bodyLg, color = C.danger, maxLines = 2)
                Txt("Press Back to go back, or OK to try again.", T.body, color = C.inkMuted)
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }

        if ((overlay || !playing) && !panelOpen) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, C.scrimStrong)))
                    .padding(start = S.safeX, end = S.safeX, bottom = S.safeY, top = 60.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt(item.title, T.titleLg)
                item.subtitle?.let { Txt(it, T.body, color = C.inkMuted) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s3)) {
                    Txt(if (playing) "▶" else "❚❚", T.label, color = C.accent)
                    Txt(clock(position), T.label)
                    ProgressBar(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f), height = 6.dp)
                    Txt(if (duration > 0) "-" + clock(duration - position) else "--:--", T.label, color = C.inkMuted)
                }
                Txt("◀ ▶ seek (hold to go faster)  ·  OK pause  ·  ▲ audio, subtitles & picture", T.caption, color = C.inkFaint)
            }
        }

        if (ended && hasNext) {
            val next = queue[index + 1]
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = S.safeX, bottom = S.safeY + 90.dp)
                    .clip(RoundedCornerShape(S.radiusLg)).background(C.bg100.copy(alpha = 0.95f)).padding(S.s4),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt("UP NEXT", T.section)
                Txt(next.subtitle ?: next.title, T.title)
                Txt("Starts in $countdown s · OK to play now · Back to stop", T.caption, color = C.inkMuted)
            }
        }

        if (panelOpen) {
            TracksPanel(
                choices = trackChoices(player, tracks),
                fit = fit,
                onFit = { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size] },
                onClose = { panelOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        notice?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = S.safeY)) }
    }
}

@OptIn(UnstableApi::class)
private fun trackChoices(player: ExoPlayer, tracks: Tracks): Pair<List<TrackChoice>, List<TrackChoice>> {
    fun label(group: Tracks.Group, i: Int): String {
        val f = group.getTrackFormat(i)
        val lang = f.language?.let { Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() && d != it } ?: it }
        return listOfNotNull(lang ?: f.label ?: "Unknown", f.label?.takeIf { it != lang }, f.channelCount.takeIf { it > 2 }?.let { "$it ch" })
            .joinToString(" · ")
    }
    val audio = ArrayList<TrackChoice>()
    val text = ArrayList<TrackChoice>()
    val textOff = player.trackSelectionParameters.disabledTrackTypes.contains(MC.TRACK_TYPE_TEXT)
    text += TrackChoice("Off", textOff || tracks.groups.none { it.type == MC.TRACK_TYPE_TEXT && it.isSelected }) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true).build()
    }
    for (g in tracks.groups) {
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val apply = {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(g.type, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
            }
            when (g.type) {
                MC.TRACK_TYPE_AUDIO -> audio += TrackChoice(label(g, i), g.isTrackSelected(i), apply)
                MC.TRACK_TYPE_TEXT -> text += TrackChoice(label(g, i), !textOff && g.isTrackSelected(i), apply)
            }
        }
    }
    return audio to text
}

@Composable
private fun TracksPanel(
    choices: Pair<List<TrackChoice>, List<TrackChoice>>,
    fit: Fit,
    onFit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val (audio, text) = choices
    LazyColumn(
        modifier.width(340.dp).fillMaxHeight().background(C.bg100.copy(alpha = 0.97f))
            .padding(start = S.s4, end = S.safeX - S.s4, top = S.safeY, bottom = S.safeY),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Txt("SUBTITLES", T.section, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)) }
        if (text.size == 1) item { Txt("This file has no subtitles.", T.caption, color = C.inkFaint, modifier = Modifier.padding(start = 12.dp)) }
        items(text.size) { i ->
            val c = text[i]
            FocusRow(modifier = if (i == 0) Modifier.focusRequester(first) else Modifier, selected = c.selected, onClick = { c.apply(); onClose() }) {
                Txt((if (c.selected) "✓  " else "     ") + c.label, T.label, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        item { Txt("AUDIO", T.section, modifier = Modifier.padding(start = 12.dp, top = S.s3, bottom = 4.dp)) }
        items(audio.size) { i ->
            val c = audio[i]
            FocusRow(selected = c.selected, onClick = { c.apply(); onClose() }) {
                Txt((if (c.selected) "✓  " else "     ") + c.label, T.label, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        item { Txt("PICTURE", T.section, modifier = Modifier.padding(start = 12.dp, top = S.s3, bottom = 4.dp)) }
        item {
            FocusRow(onClick = onFit) { Txt("Aspect: ${fit.label}", T.label, modifier = Modifier.padding(horizontal = 12.dp)) }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
