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
    val onLeft: (() -> Unit)? = null,
    val onRight: (() -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
)

/** Another copy of what's playing (other language or quality) to switch to. */
private data class Version(val label: String, val switch: suspend () -> Unit)

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

    // ---- Versions, intro, credits
    var versions by remember { mutableStateOf<List<Version>>(emptyList()) }
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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) save()
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
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

    // Other versions of what's playing (other language or quality).
    LaunchedEffect(index, item.id) {
        versions = runCatching {
            if (item.kind == "movie") {
                val m = app.db.vod().movie(loginId, item.id) ?: return@runCatching emptyList()
                app.db.vod().movieVersions(loginId, m.titleKey)
                    .filter { it.streamId != m.streamId && (it.year == null || m.year == null || it.year == m.year) }
                    .map { v ->
                        Version(listOfNotNull(VodNaming.source(v.rawName)?.let { "$it · ${VodNaming.sourceDescription(it)}" } ?: "Other source", Naming.qualityLabel(v.quality)).joinToString(" · ")) {
                            keepAt = player.currentPosition
                            q = q.toMutableList().also { it[index] = it[index].copy(id = v.streamId, ext = v.ext) }
                        }
                    }
            } else {
                val s = app.db.vod().series(loginId, item.seriesId) ?: return@runCatching emptyList()
                app.db.vod().seriesByKeys(loginId, listOf(s.titleKey)).filter { it.seriesId != s.seriesId }.map { other ->
                    Version((VodNaming.source(other.rawName)?.let { "$it · ${VodNaming.sourceDescription(it)}" } ?: "Other source") + " · S${item.season}:E${item.episode}") {
                        val info = app.vod.seriesInfo(session.login, other.seriesId)
                        val all = info.seasons.flatMap { it.episodes }
                        val k = all.indexOfFirst { it.season == item.season && it.number == item.episode }
                        if (k < 0) { notice = "That version doesn't have S${item.season}:E${item.episode}"; return@Version }
                        val src = VodNaming.source(other.rawName)?.let(VodNaming::sourceDescription)
                        keepAt = player.currentPosition
                        q = all.map { e ->
                            PlayItem("episode", e.id, other.seriesId, e.season, e.number, item.title,
                                listOfNotNull("S${e.season}:E${e.number} · ${e.title}", src).joinToString(" · "),
                                e.image ?: info.backdrop ?: item.image, e.ext, e.durationMs)
                        }
                        index = k
                    }
                }
            }
        }.getOrDefault(emptyList())
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
    LaunchedEffect(overlayToken) { overlay = true; delay(4_000); if (player.isPlaying) overlay = false }
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

    BackHandler {
        when {
            panelOpen -> panelOpen = false
            nextPrompt -> {
                // Keep watching: the credits started later than thought, so next time ask later.
                nextPrompt = false
                nextDismissed = true
                if (isEpisode && duration > 0) app.prefs.setCredits(item.seriesId, (duration - position) - 10_000)
            }
            else -> onExit()
        }
    }

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
            update = { it.resizeMode = fit.mode },
            modifier = Modifier.fillMaxSize(),
        )

        // SubDL subtitles, drawn by the app so timing changes apply instantly.
        cueText?.let { t ->
            Txt(
                t,
                T.titleLg.copy(
                    fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = Color.White,
                    shadow = Shadow(Color.Black, blurRadius = 6f),
                ),
                maxLines = 3,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 80.dp, end = 80.dp, bottom = if (overlay || !playing) 130.dp else 36.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
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
                Txt("◀ ▶ seek (hold to go faster)  ·  OK pause  ·  ▲ subtitles, audio, versions", T.caption, color = C.inkFaint)
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
            val rows = buildList {
                add(PanelRow("SUBTITLES", header = true))
                val (audio, text) = trackChoices(player, tracks)
                add(PanelRow("Off", selected = !extOn && text.first().selected, onClick = { useExternal(false); text.first().apply() }))
                text.drop(1).forEach { c -> add(PanelRow(c.label + "  (in file)", selected = !extOn && c.selected, onClick = { useExternal(false); c.apply() })) }
                subOptions.take(8).forEachIndexed { i, o ->
                    add(PanelRow("Arabic · ${o.release}", selected = extOn && subChoice == i, onClick = { loadSub(i) }))
                }
                subStatus?.let { add(PanelRow(it, note = true)) }
                if (extOn) {
                    add(PanelRow("SUBTITLE TIMING", header = true))
                    val sign = if (subDelay > 0) "+" else ""
                    add(PanelRow(
                        "◀  ${sign}${"%.1f".format(subDelay / 1000f)} s  ▶   " + when {
                            subDelay > 0 -> "(later)"; subDelay < 0 -> "(earlier)"; else -> "(as is)"
                        },
                        onLeft = { subDelay -= 250; app.prefs.setSubDelay(delayKey, subDelay) },
                        onRight = { subDelay += 250; app.prefs.setSubDelay(delayKey, subDelay) },
                        onClick = { subDelay = 0; app.prefs.setSubDelay(delayKey, 0) },
                    ))
                    add(PanelRow("◀ ▶ shift by ¼ s · OK resets. Saved for this ${if (isEpisode) "show" else "movie"}.", note = true))
                }
                if (audio.isNotEmpty()) {
                    add(PanelRow("AUDIO", header = true))
                    audio.forEach { c -> add(PanelRow(c.label, selected = c.selected, onClick = c.apply)) }
                }
                if (versions.isNotEmpty()) {
                    add(PanelRow("OTHER VERSIONS", header = true))
                    versions.forEach { v -> add(PanelRow(v.label, onClick = { scope.launch { v.switch() }; panelOpen = false })) }
                }
                if (hasNext) {
                    add(PanelRow("EPISODE", header = true))
                    add(PanelRow("Next episode ▸", onClick = { panelOpen = false; nextNow() }))
                }
                add(PanelRow("PICTURE", header = true))
                add(PanelRow("Aspect: ${fit.label}", onClick = { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size] }))
            }
            OptionsPanel(rows, onClose = { panelOpen = false }, modifier = Modifier.align(Alignment.CenterEnd))
        }

        notice?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = S.safeY)) }
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

@Composable
private fun OptionsPanel(rows: List<PanelRow>, onClose: () -> Unit, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val firstAction = rows.indexOfFirst { !it.header && !it.note }
    LazyColumn(
        modifier.width(380.dp).fillMaxHeight().background(C.bg100.copy(alpha = 0.97f))
            .padding(start = S.s4, end = S.safeX - S.s4, top = S.safeY, bottom = S.safeY),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(rows.size) { i ->
            val r = rows[i]
            when {
                r.header -> Txt(r.label, T.section, modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else S.s3, bottom = 4.dp))
                r.note -> Txt(r.label, T.caption, color = C.inkFaint, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp))
                else -> FocusRow(
                    modifier = (if (i == firstAction) Modifier.focusRequester(first) else Modifier).onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionLeft -> r.onLeft?.let { it(); true } ?: false
                            Key.DirectionRight -> r.onRight?.let { it(); true } ?: false
                            else -> false
                        }
                    },
                    selected = r.selected,
                    onClick = {
                        r.onClick?.invoke()
                        if (r.onLeft == null) onClose()
                    },
                ) {
                    Txt((if (r.selected) "✓  " else "     ") + r.label, T.label, modifier = Modifier.padding(horizontal = 12.dp))
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
