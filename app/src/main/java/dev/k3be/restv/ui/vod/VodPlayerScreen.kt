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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.k3be.restv.data.Cue
import dev.k3be.restv.data.Naming
import dev.k3be.restv.data.SubDl
import dev.k3be.restv.data.SubOption
import dev.k3be.restv.data.SubQuery
import dev.k3be.restv.data.VodNaming
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

/** One row in the Audio, subtitles & more panel. Left/Right act on rows that adjust a value. */
private data class PanelRow(
    val label: String,
    val selected: Boolean = false,
    val header: Boolean = false,
    val note: Boolean = false,
    val keepOpen: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/**
 * Movies and episodes: resumes where you stopped and saves every 5 s, seeks with Left/Right
 * (faster when held), Arabic subtitles from the file or SubDL with timing control, other versions,
 * and Skip intro / Next episode that learn each show's intro and credits from what you do.
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

    /** The queue can change when switching to another version of a series. */
    var q by remember { mutableStateOf(queue) }
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, queue.lastIndex)) }
    var startFresh by remember { mutableStateOf(fromStart) }
    /** Position to keep when the same title reloads from another version. */
    var keepAt by remember { mutableStateOf<Long?>(null) }
    val item = q[index]
    val isEpisode = item.kind == "episode" && item.seriesId > 0

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
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguages("en", "eng")
                    .setPreferredTextLanguages("ar", "ara", "en")
                    .setSelectUndeterminedTextLanguage(false)
                    .build()
                playWhenReady = true
            }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    /** False only when you paused (not while loading). */
    var wantsPlay by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var vFormat by remember { mutableStateOf<androidx.media3.common.Format?>(null) }
    var aFormat by remember { mutableStateOf<androidx.media3.common.Format?>(null) }
    val tvHdr = remember { dev.k3be.restv.ui.player.displayHdr(context) }
    var overlayToken by remember { mutableIntStateOf(1) }
    var overlay by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(Fit.FIT) }
    var notice by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }

    // ---- External (SubDL) subtitles
    var subOptions by remember { mutableStateOf<List<SubOption>>(emptyList()) }
    var subChoice by remember { mutableIntStateOf(-1) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var extOn by remember { mutableStateOf(false) }
    var subStatus by remember { mutableStateOf<String?>(null) }
    var subQuery by remember { mutableStateOf<SubQuery?>(null) }
    val delayKey = if (isEpisode) "s${item.seriesId}" else "m${item.id}"
    var subDelay by remember(delayKey) { mutableLongStateOf(app.prefs.subDelay(delayKey)) }
    var cueText by remember { mutableStateOf<String?>(null) }
    var subSize by remember { mutableIntStateOf(app.prefs.subSize()) }
    var subBox by remember { mutableStateOf(app.prefs.subBox()) }

    // ---- Versions, intro, credits
    var intro by remember(item.seriesId) { mutableStateOf(if (isEpisode) app.prefs.intro(item.seriesId) else null) }
    var fwdFrom by remember { mutableStateOf<Long?>(null) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    var nextPrompt by remember { mutableStateOf(false) }
    var nextDismissed by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }

    // Saves run on the app's scope: the screen's scope is already cancelled when Back closes it.
    fun save() {
        val cur = q[index]
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 && it != MC.TIME_UNSET } ?: cur.durationMs
        if (pos < 3_000 && !ended) return
        val w = WatchEntity(
            accountId, loginId, cur.kind, cur.id, cur.seriesId, cur.season, cur.episode,
            cur.title, cur.subtitle, cur.image, cur.ext,
            if (ended) dur else pos, dur, System.currentTimeMillis(),
        )
        app.appScope.launch { app.db.watch().upsert(w) }
    }

    fun useExternal(on: Boolean) {
        extOn = on
        // Never two subtitle lines at once: the file's own track is switched off while SubDL's shows.
        if (on) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true).build()
    }

    fun loadSub(i: Int) {
        val opt = subOptions.getOrNull(i) ?: return
        val query = subQuery ?: return
        scope.launch {
            subStatus = "Loading subtitles…"
            runCatching { app.subdl.cues(opt, query) }
                .onSuccess { c -> cues = c; subChoice = i; useExternal(true); subStatus = null }
                .onFailure { subStatus = it.message ?: "Couldn't load that subtitle" }
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) { duration = player.duration.coerceAtLeast(0); error = null; retries = 0 }
                if (state == Player.STATE_ENDED) { ended = true; save() }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { wantsPlay = playWhenReady }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) save()
            }
            override fun onTracksChanged(t: Tracks) { tracks = t; vFormat = player.videoFormat; aFormat = player.audioFormat }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { vFormat = player.videoFormat }
            override fun onPlayerError(e: PlaybackException) {
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
    LaunchedEffect(index, item.id) {
        ended = false
        error = null
        retries = 0
        nextPrompt = false
        nextDismissed = false
        val url = app.xtream.vodUrl(session.login, if (item.kind == "movie") "movie" else "series", item.id, item.ext)
        val saved = app.db.watch().get(accountId, loginId, item.kind, item.id)
        val resumeAt = keepAt ?: if (!startFresh && saved != null && !saved.finished && saved.positionMs > 30_000) saved.positionMs - 5_000 else 0L
        keepAt = null
        startFresh = false
        player.setMediaItem(MediaItem.fromUri(url), resumeAt)
        player.prepare()
        player.play()
        if (resumeAt > 0) notice = "Resuming from ${clock(resumeAt)}"
        overlayToken++
    }

    // Arabic subtitles: the file's own Arabic track if it has one, otherwise SubDL.
    LaunchedEffect(index, item.id) {
        subOptions = emptyList(); cues = emptyList(); subChoice = -1; extOn = false; subStatus = null; cueText = null
        val key = app.prefs.subdlKey()
        if (key == null) { subStatus = "Add a free SubDL key in Settings to get Arabic subtitles for everything."; return@LaunchedEffect }
        subStatus = "Looking for Arabic subtitles…"
        val query = runCatching {
            if (item.kind == "movie") {
                val info = app.vod.movieInfo(session.login, item.id)
                SubQuery(info.title.ifBlank { item.title }, info.year, info.tmdbId, false)
            } else {
                val info = app.vod.seriesInfo(session.login, item.seriesId)
                SubQuery(info.title.ifBlank { item.title }, info.year, info.tmdbId, true, item.season, item.episode)
            }
        }.getOrElse { SubQuery(item.title, null, null, item.kind == "episode", item.season, item.episode) }
        subQuery = query
        val found = runCatching { app.subdl.search(key, query) }
        found.onFailure { subStatus = it.message ?: "SubDL didn't answer" }
        val list = found.getOrNull() ?: return@LaunchedEffect
        subOptions = list
        if (list.isEmpty()) { subStatus = "No Arabic subtitles on SubDL for this yet."; return@LaunchedEffect }
        subStatus = null
        delay(1_500) // let the file's own tracks appear first
        val embeddedArabic = tracks.groups.any { g ->
            g.type == MC.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).language?.lowercase()?.startsWith("ar") == true }
        }
        if (!embeddedArabic) loadSub(0)
    }

    // The subtitle line on screen, shifted by the chosen timing.
    LaunchedEffect(cues, extOn) {
        if (!extOn || cues.isEmpty()) { cueText = null; return@LaunchedEffect }
        while (isActive) {
            cueText = SubDl.at(cues, player.currentPosition - subDelay)?.text
            delay(100)
        }
    }

    // Clock for the progress bar, plus a save every 5 s.
    LaunchedEffect(Unit) {
        var tick = 0
        while (isActive) {
            position = player.currentPosition
            if (player.duration > 0) duration = player.duration
            if (++tick % 10 == 0 && player.isPlaying) save()
            delay(500)
        }
    }
    // Hides unless you paused: a new episode still loading at that moment no longer keeps it on screen.
    LaunchedEffect(overlayToken) { overlay = true; delay(if (overlayToken <= 2) 7_000 else 4_000); if (player.playWhenReady) overlay = false }
    LaunchedEffect(notice) { if (notice != null) { delay(3_500); notice = null } }

    val hasNext = index < q.lastIndex && q[index + 1].kind == "episode"
    fun goNext() { save(); nextPrompt = false; index++ }

    // Next episode: offered when the credits start (learned per show, 40 s until it knows), plays after 5 s.
    val credits = if (isEpisode) app.prefs.credits(item.seriesId) else 0L
    LaunchedEffect(position, ended) {
        if (!hasNext || nextPrompt) return@LaunchedEffect
        val inCredits = duration > 0 && position > duration / 2 && duration - position <= credits
        if ((inCredits && !nextDismissed) || ended) nextPrompt = true
    }
    LaunchedEffect(nextPrompt) {
        if (!nextPrompt) return@LaunchedEffect
        for (n in 5 downTo 1) { countdown = n; delay(1_000) }
        goNext()
    }

    // Skip intro: learned the first time you jump over a show's intro with ▶▶; offered on later episodes.
    LaunchedEffect(lastSeekAt) {
        if (lastSeekAt == 0L) return@LaunchedEffect
        delay(1_500)
        val from = fwdFrom ?: return@LaunchedEffect
        fwdFrom = null
        val to = player.currentPosition
        if (isEpisode && to - from in 20_000..240_000 && to <= 9 * 60_000) {
            app.prefs.setIntro(item.seriesId, from, to)
            intro = from to to
            notice = "Intro learned: “Skip intro” will appear on the next episodes"
        }
    }
    val showSkipIntro = intro?.let { (a, b) -> position in (a - 2_000)..(b - 4_000) } == true

    val focus = remember { FocusRequester() }
    LaunchedEffect(panelOpen) { if (!panelOpen) runCatching { focus.requestFocus() } }

    var lastBack by remember { mutableLongStateOf(0L) }
    var exitHint by remember { mutableStateOf(false) }
    BackHandler {
        when {
            panelOpen -> panelOpen = false
            nextPrompt -> {
                // Keep watching: the credits started later than thought, so next time ask later.
                nextPrompt = false
                nextDismissed = true
                if (isEpisode && duration > 0) app.prefs.setCredits(item.seriesId, (duration - position) - 10_000)
            }
            // First Back clears the info bar; leaving takes two presses, like live TV.
            overlay && playing -> overlay = false
            System.currentTimeMillis() - lastBack < dev.k3be.restv.BACK_CONFIRM_MS -> onExit()
            else -> { lastBack = System.currentTimeMillis(); exitHint = true }
        }
    }
    LaunchedEffect(exitHint) { if (exitHint) { delay(dev.k3be.restv.BACK_CONFIRM_MS); exitHint = false } }

    fun seek(deltaMs: Long) {
        val now = player.currentPosition
        if (deltaMs > 0 && isEpisode && now < 6 * 60_000 && fwdFrom == null) fwdFrom = now
        if (deltaMs < 0) fwdFrom = null
        lastSeekAt = System.currentTimeMillis()
        val d = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((now + deltaMs).coerceIn(0, d - 1_000))
        position = player.currentPosition
        overlayToken++
    }

    fun nextNow() {
        // Moving on before the credits we know about teaches when this show's credits begin.
        val remaining = duration - position
        if (isEpisode && duration > 0 && remaining in (credits + 1)..(8 * 60_000)) app.prefs.setCredits(item.seriesId, remaining)
        goNext()
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
                        when {
                            error != null -> { error = null; retries = 0; player.prepare(); player.play() }
                            nextPrompt && hasNext -> nextNow()
                            showSkipIntro -> { intro?.let { player.seekTo(it.second) }; overlayToken++ }
                            player.isPlaying -> { player.pause(); overlayToken++ }
                            else -> { player.play(); overlayToken++ }
                        }
                        true
                    }
                    Key.MediaPlay -> { player.play(); true }
                    Key.MediaPause -> { player.pause(); overlayToken++; true }
                    Key.DirectionUp, Key.Menu -> { panelOpen = true; true }
                    Key.DirectionDown -> { overlayToken++; true }
                    Key.MediaNext -> { if (hasNext) nextNow(); true }
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
            update = {
                it.resizeMode = fit.mode
                // Subtitles from the file follow the same size and background choice.
                it.subtitleView?.setFractionalTextSize(listOf(0.045f, 0.058f, 0.072f)[subSize])
                it.subtitleView?.setStyle(CaptionStyleCompat(
                    android.graphics.Color.WHITE, if (subBox) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null,
                ))
            },
            modifier = Modifier.fillMaxSize(),
        )

        // SubDL subtitles, drawn by the app so timing changes apply instantly.
        cueText?.let { t ->
            Txt(
                t,
                T.titleLg.copy(
                    fontSize = listOf(21, 26, 32)[subSize].sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = Color.White,
                    shadow = Shadow(Color.Black, blurRadius = 6f),
                ),
                maxLines = 3,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 80.dp, end = 80.dp, bottom = if (overlay || !wantsPlay) 130.dp else 36.dp)
                    .background(Color.Black.copy(alpha = if (subBox) 0.5f else 0f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

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

        if ((overlay || !wantsPlay) && !panelOpen) {
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
                // What's actually playing: picture, dynamic range, sound, subtitles.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val vf = vFormat
                    val range = dev.k3be.restv.ui.player.dynamicRange(vf)
                    dev.k3be.restv.ui.player.resolutionLabel(vf?.width ?: 0, vf?.height ?: 0)?.let { Chip(it, if (it == "4K") C.uhd else C.ink) }
                    Chip(range ?: "SDR", if (range != null) C.uhd else C.inkMuted)
                    dev.k3be.restv.ui.player.audioLabel(aFormat)?.let { Chip(it, C.ink) }
                    val subLabel = when {
                        extOn -> "Arabic subtitles"
                        tracks.groups.any { it.type == MC.TRACK_TYPE_TEXT && it.isSelected } -> "Subtitles from file"
                        else -> null
                    }
                    subLabel?.let { Chip(it, C.ink) }
                    if (range != null && tvHdr == null) Txt("· this TV didn't report HDR support", T.caption, color = C.inkFaint)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s3)) {
                    Txt(if (playing) "▶" else "❚❚", T.label, color = C.accent)
                    Txt(clock(position), T.label)
                    ProgressBar(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f), height = 6.dp)
                    Txt(if (duration > 0) "-" + clock(duration - position) else "--:--", T.label, color = C.inkMuted)
                }
                Txt("◀ ▶ seek (hold to go faster)  ·  OK pause  ·  ▲ audio & subtitles", T.caption, color = C.inkFaint)
            }
        }

        if (showSkipIntro && !panelOpen) {
            Txt(
                "Skip intro  ·  OK", T.label, color = C.focusInk,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = S.safeX, bottom = S.safeY + 110.dp)
                    .background(C.focus, RoundedCornerShape(S.radiusMd)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        if (nextPrompt && hasNext && !panelOpen) {
            val next = q[index + 1]
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = S.safeX, bottom = S.safeY + 110.dp)
                    .clip(RoundedCornerShape(S.radiusLg)).background(C.bg100.copy(alpha = 0.95f)).padding(S.s4),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt("NEXT EPISODE IN $countdown", T.section)
                Txt(next.subtitle ?: next.title, T.title)
                Txt("OK play now  ·  Back keep watching", T.caption, color = C.inkMuted)
            }
        }

        if (panelOpen) {
            val (audio, text) = trackChoices(player, tracks)
            val audioRows = audio.map { c -> PanelRow(c.label, selected = c.selected, onClick = c.apply) }
                .ifEmpty { listOf(PanelRow("Only one audio track", note = true)) }
            val subRows = buildList {
                add(PanelRow("Off", selected = !extOn && text.first().selected, onClick = { useExternal(false); text.first().apply() }))
                subOptions.take(8).forEachIndexed { i, o ->
                    add(PanelRow("Arabic · ${o.release.take(34)}", selected = extOn && subChoice == i, onClick = { loadSub(i) }))
                }
                text.drop(1).forEach { c -> add(PanelRow("${c.label} · in file", selected = !extOn && c.selected, onClick = { useExternal(false); c.apply() })) }
                subStatus?.let { add(PanelRow(it, note = true)) }
            }
            val sign = if (subDelay > 0) "+" else ""
            val timingRows = buildList {
                if (extOn) {
                    add(PanelRow("Timing ${sign}${"%.2f".format(subDelay / 1000f)} s", note = true))
                    add(PanelRow("◀  Show earlier", onClick = { subDelay -= 250; app.prefs.setSubDelay(delayKey, subDelay) }, keepOpen = true))
                    add(PanelRow("▶  Show later", onClick = { subDelay += 250; app.prefs.setSubDelay(delayKey, subDelay) }, keepOpen = true))
                    add(PanelRow("Reset timing", onClick = { subDelay = 0; app.prefs.setSubDelay(delayKey, 0) }, keepOpen = true))
                } else {
                    add(PanelRow("Timing works on the Arabic (SubDL) subtitles; pick one on the left.", note = true))
                }
                add(PanelRow("Size: ${listOf("Small", "Medium", "Large")[subSize]}", onClick = { subSize = (subSize + 1) % 3; app.prefs.setSubSize(subSize) }, keepOpen = true))
                add(PanelRow("Background: ${if (subBox) "On" else "Off"}", onClick = { subBox = !subBox; app.prefs.setSubBox(subBox) }, keepOpen = true))
            }
            val moreRows = buildList {
                add(PanelRow("Picture: ${fit.label}", onClick = { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size] }, keepOpen = true))
                if (hasNext) add(PanelRow("Next episode ▸", onClick = { panelOpen = false; nextNow() }))
            }
            AvPanel(
                audio = audioRows,
                subtitles = subRows,
                timing = timingRows,
                more = moreRows,
                onClose = { panelOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        notice?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = S.safeY)) }
        if (exitHint) Toast("Press Back again to stop watching", Modifier.align(Alignment.TopCenter).padding(top = S.safeY + 48.dp))
    }
}

private data class TrackChoice(val label: String, val selected: Boolean, val apply: () -> Unit)

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

/** Netflix-style sheet: Audio | Subtitles | Timing | More, side by side; Left/Right moves between them. */
@Composable
private fun AvPanel(
    audio: List<PanelRow>,
    subtitles: List<PanelRow>,
    timing: List<PanelRow>,
    more: List<PanelRow>,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, C.bg000.copy(alpha = 0.92f), C.bg000)))
            .padding(start = S.safeX, end = S.safeX, top = 70.dp, bottom = S.safeY),
        horizontalArrangement = Arrangement.spacedBy(S.s6),
    ) {
        PanelColumn("Audio", audio, null, onClose, Modifier.weight(1f))
        PanelColumn("Subtitles", subtitles, start, onClose, Modifier.weight(1.3f))
        PanelColumn("Subtitle style", timing, null, onClose, Modifier.weight(1f))
        PanelColumn("More", more, null, onClose, Modifier.weight(0.8f))
    }
}

@Composable
private fun PanelColumn(title: String, rows: List<PanelRow>, focus: FocusRequester?, onClose: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(title, T.title, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        val target = rows.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: rows.indexOfFirst { !it.note }
        LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows.size) { i ->
                val r = rows[i]
                if (r.note) Txt(r.label, T.caption, color = C.inkFaint, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                else FocusRow(
                    modifier = if (focus != null && i == target) Modifier.focusRequester(focus) else Modifier,
                    selected = r.selected,
                    height = 44.dp,
                    onClick = { r.onClick?.invoke(); if (!r.keepOpen) onClose() },
                ) { _ ->
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(if (r.selected) "✓" else "", T.label, color = C.accent, modifier = Modifier.width(20.dp))
                        Txt(r.label, T.label, color = if (r.selected) C.ink else C.inkMuted)
                    }
                }
            }
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
