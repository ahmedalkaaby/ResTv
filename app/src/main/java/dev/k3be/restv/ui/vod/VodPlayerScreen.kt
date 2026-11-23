package dev.k3be.restv.ui.vod

import dev.k3be.restv.ui.components.glass

import android.view.View
import androidx.activity.compose.BackHandler
import dev.k3be.restv.ui.components.backKey
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
import androidx.compose.foundation.layout.height
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
internal data class PanelRow(
    val label: String,
    val selected: Boolean = false,
    val header: Boolean = false,
    val note: Boolean = false,
    val keepOpen: Boolean = false,
    val onClick: (() -> Unit)? = null,
    /** Shown on the right of the row, e.g. "Medium". */
    val value: String? = null,
    /** A second, smaller line, e.g. the codec. */
    val detail: String? = null,
    val onFocus: (() -> Unit)? = null,
)

/** One button of the player's bottom bar. */
internal data class BarItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val action: () -> Unit)

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
    val accountId = session.viewer
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
                    .setPreferredTextLanguages("ar", "ara")
                    .setSelectUndeterminedTextLanguage(false)
                    // Subtitles start off; the effect below turns on Arabic from the file or SubDL.
                    .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true)
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
    var style by remember { mutableStateOf(app.prefs.subStyle()) }
    fun restyle(v: dev.k3be.restv.data.SubStyle) { style = v; app.prefs.setSubStyle(v) }
    // Which sheet is open (subtitles, audio, more), the language being browsed in the subtitles
    // sheet, and the focused bottom-bar button (-1 = none: Left/Right seek).
    var panelKind by remember { mutableStateOf("subs") }
    var subLang by remember { mutableStateOf<String?>(null) }
    var bar by remember { mutableIntStateOf(-1) }

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

    /** "AR-SUBS" titles have Arabic burned into the picture: no subtitle line on top of them. */
    suspend fun burnedInArabic(): Boolean = runCatching {
        val (raw, catId, kind) = if (item.kind == "movie") {
            val m = app.db.vod().movie(loginId, item.id) ?: return false
            Triple(m.rawName, m.categoryId, "movie")
        } else {
            val sr = app.db.vod().series(loginId, item.seriesId) ?: return false
            Triple(sr.rawName, sr.categoryId, "series")
        }
        val cat = app.db.vod().categories(loginId, kind).firstOrNull { it.categoryId == catId }?.rawName
        VodNaming.hasArabicSubs(raw, cat)
    }.getOrDefault(false)

    // Subtitles, per title:
    //  - AR-SUBS source (Arabic in the picture) → off;
    //  - the file has an Arabic track → that track;
    //  - otherwise → the best SubDL match. Other languages in the file are never switched on by themselves.
    LaunchedEffect(index, item.id) {
        subOptions = emptyList(); cues = emptyList(); subChoice = -1; extOn = false; subStatus = null; cueText = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(MC.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true)
            .build()
        val burned = burnedInArabic()
        // Let the file's own tracks appear.
        var waited = 0
        while (tracks.groups.none { it.type == MC.TRACK_TYPE_AUDIO || it.type == MC.TRACK_TYPE_VIDEO } && waited < 8_000) { delay(250); waited += 250 }
        val embeddedArabic = tracks.groups.any { g ->
            g.type == MC.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).language?.lowercase()?.startsWith("ar") == true }
        }
        if (!burned && embeddedArabic) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, false).build()
        }
        if (burned) notice = "Arabic subtitles are in the picture · ▲ to add others"

        val key = app.prefs.subdlKey()
        if (key == null) {
            if (!burned && !embeddedArabic) subStatus = "Add a free SubDL key in Settings to get Arabic subtitles for everything."
            return@LaunchedEffect
        }
        // Still looked up for AR-SUBS and files with Arabic, so ▲ can offer SubDL; only used by itself when needed.
        if (!burned && !embeddedArabic) subStatus = "Looking for Arabic subtitles…"
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
        val list = found.getOrNull()
        if (list == null) { if (!burned && !embeddedArabic) subStatus = found.exceptionOrNull()?.message ?: "SubDL didn't answer"; return@LaunchedEffect }
        subOptions = list
        if (burned || embeddedArabic) { subStatus = null; return@LaunchedEffect }
        if (list.isEmpty()) { subStatus = "No Arabic subtitles on SubDL for this yet."; return@LaunchedEffect }
        subStatus = null
        loadSub(0)
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
    LaunchedEffect(overlayToken) { overlay = true; delay(if (overlayToken <= 2) 7_000 else 4_000); if (player.playWhenReady) { overlay = false; bar = -1 } }
    LaunchedEffect(notice) { if (notice != null) { delay(3_500); notice = null } }

    val hasNext = index < q.lastIndex && q[index + 1].kind == "episode"
    fun goNext() { save(); nextPrompt = false; index++ }

    // Intro, recap, credits and preview times from TheIntroDB, looked up once the length is known.
    var segs by remember(index) { mutableStateOf<List<dev.k3be.restv.data.Segment>>(emptyList()) }
    LaunchedEffect(index, duration > 0) {
        if (duration <= 0) return@LaunchedEffect
        val tmdb = runCatching {
            if (item.kind == "movie") {
                val i = app.vod.movieInfo(session.login, item.id)
                i.tmdbId?.takeIf { it.all(Char::isDigit) } ?: app.art.details("movie", i.title.ifBlank { item.title }, i.year, null)?.id
            } else {
                val i = app.vod.seriesInfo(session.login, item.seriesId)
                i.tmdbId?.takeIf { it.all(Char::isDigit) } ?: app.art.details("series", i.title.ifBlank { item.title }, i.year, null)?.id
            }
        }.getOrNull() ?: return@LaunchedEffect
        segs = app.segments.get(tmdb, item.season.takeIf { isEpisode }, item.episode.takeIf { isEpisode }, duration)
    }

    // Next episode: offered when the credits start (TheIntroDB, else learned per show, 40 s until it knows), plays after 5 s.
    val credits = if (isEpisode) app.prefs.credits(item.seriesId) else 0L
    val creditsStart = segs.firstOrNull { it.kind == "credits" }?.start
    LaunchedEffect(position, ended) {
        if (!hasNext || nextPrompt) return@LaunchedEffect
        val inCredits = duration > 0 && position > duration / 2 &&
            (if (creditsStart != null) position >= creditsStart else duration - position <= credits)
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
        if (isEpisode && app.prefs.introLearning(item.seriesId) && to - from in 20_000..240_000 && to <= 9 * 60_000) {
            app.prefs.setIntro(item.seriesId, from, to)
            intro = from to to
            notice = "Intro saved for this show · change it under ▲ → More"
        }
    }
    // The Skip intro button: offered for 10 s when the intro starts, taken with ▶ (OK always pauses),
    // dismissed with Back. With auto-skip on, it jumps by itself.
    // What can be skipped here: TheIntroDB's intro / recap / preview, else the intro you taught it.
    val skips = buildList {
        addAll(segs.filter { it.kind != "credits" })
        if (segs.none { it.kind == "intro" }) intro?.let { add(dev.k3be.restv.data.Segment("intro", it.first, it.second)) }
    }
    val skipNow = skips.firstOrNull { s -> val e = s.end ?: duration; e > 0 && position in (s.start - 2_000)..(e - 4_000) }
    val skipTo = skipNow?.let { it.end ?: duration }
    val skipLabel = when (skipNow?.kind) { "recap" -> "Skip recap"; "preview" -> "Skip preview"; else -> "Skip intro" }
    val inIntro = skipNow != null
    var introDismissed by remember(index, skipNow?.start) { mutableStateOf(false) }
    var introOffer by remember(index) { mutableStateOf(false) }
    LaunchedEffect(skipNow?.kind, skipNow?.start) {
        if (skipNow == null) { introOffer = false; return@LaunchedEffect }
        val auto = if (isEpisode) app.prefs.autoSkipIntro(item.seriesId) else false
        if (auto && skipNow.kind != "preview" && skipTo != null) {
            player.seekTo(skipTo)
            notice = if (skipNow.kind == "recap") "Skipped the recap" else "Skipped the intro"
            return@LaunchedEffect
        }
        introOffer = true
        delay(10_000)
        introOffer = false
    }
    val showSkipIntro = inIntro && introOffer && !introDismissed

    val focus = remember { FocusRequester() }
    LaunchedEffect(panelOpen) { if (!panelOpen) { subLang = null; runCatching { focus.requestFocus() } } }

    var lastBack by remember { mutableLongStateOf(0L) }
    var exitHint by remember { mutableStateOf(false) }
    fun handleBack() {
        when {
            panelOpen -> panelOpen = false
            bar >= 0 -> bar = -1
            showSkipIntro -> introDismissed = true
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
    BackHandler { handleBack() }
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

    val barItems = buildList {
        add(BarItem(dev.k3be.restv.ui.components.Icons.back10, "Back 10 s") { seek(-10_000) })
        add(BarItem(if (playing) dev.k3be.restv.ui.components.Icons.pause else dev.k3be.restv.ui.components.Icons.play, if (playing) "Pause" else "Play") {
            if (player.isPlaying) player.pause() else player.play()
        })
        add(BarItem(dev.k3be.restv.ui.components.Icons.fwd10, "Forward 10 s") { seek(10_000) })
        add(BarItem(dev.k3be.restv.ui.components.Icons.subtitles, "Subtitles") { panelKind = "subs"; panelOpen = true })
        add(BarItem(dev.k3be.restv.ui.components.Icons.audio, "Audio") { panelKind = "audio"; panelOpen = true })
        if (hasNext) add(BarItem(dev.k3be.restv.ui.components.Icons.skipNext, "Next episode") { nextNow() })
        add(BarItem(dev.k3be.restv.ui.components.Icons.aspect, "Picture · ${fit.label}") { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size] })
        add(BarItem(dev.k3be.restv.ui.components.Icons.more, "More") { panelKind = "more"; panelOpen = true })
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .backKey { handleBack() }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (panelOpen || ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = ev.nativeKeyEvent.repeatCount
                // The bottom bar has focus: Left/Right move along it, OK presses, Up leaves it.
                if (bar >= 0) {
                    overlayToken++
                    when (ev.key) {
                        Key.DirectionLeft -> { bar = (bar - 1).coerceAtLeast(0); return@onKeyEvent true }
                        Key.DirectionRight -> { bar = (bar + 1).coerceAtMost(barItems.lastIndex); return@onKeyEvent true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (repeat == 0) barItems.getOrNull(bar)?.action?.invoke()
                            return@onKeyEvent true
                        }
                        Key.DirectionUp -> { bar = -1; return@onKeyEvent true }
                        Key.DirectionDown -> return@onKeyEvent true
                        else -> {}
                    }
                }
                val step = when { repeat < 3 -> 10_000L; repeat < 12 -> 30_000L; else -> 60_000L }
                when (ev.key) {
                    Key.DirectionLeft, Key.MediaRewind -> { seek(-step); true }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        if (showSkipIntro && repeat == 0) { skipTo?.let { player.seekTo(it) }; introDismissed = true; overlayToken++ }
                        else seek(step)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        if (repeat > 0) return@onKeyEvent true
                        when {
                            error != null -> { error = null; retries = 0; player.prepare(); player.play() }
                            nextPrompt && hasNext -> nextNow()
                            player.isPlaying -> { player.pause(); overlayToken++ }
                            else -> { player.play(); overlayToken++ }
                        }
                        true
                    }
                    Key.MediaPlay -> { player.play(); true }
                    Key.MediaPause -> { player.pause(); overlayToken++; true }
                    Key.DirectionUp -> { panelKind = "subs"; panelOpen = true; true }
                    Key.Menu -> { panelKind = "more"; panelOpen = true; true }
                    Key.DirectionDown -> { overlayToken++; bar = 1; true }
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
                it.subtitleView?.setFractionalTextSize(style.fraction)
                it.subtitleView?.setStyle(CaptionStyleCompat(
                    style.argb, if (style.background) 0xB3000000.toInt() else android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    when { style.outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE; style.shadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW; else -> CaptionStyleCompat.EDGE_TYPE_NONE },
                    android.graphics.Color.BLACK,
                    if (style.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT,
                ))
            },
            modifier = Modifier.fillMaxSize(),
        )

        // SubDL subtitles, drawn by the app so timing changes apply instantly.
        cueText?.let { t ->
            SubtitleLine(
                t, style,
                Modifier.align(Alignment.BottomCenter)
                    .padding(start = 80.dp, end = 80.dp, bottom = if (overlay || !wantsPlay) 150.dp else 36.dp),
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
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, C.bg000.copy(alpha = 0.55f)))))
            // Transport: a floating glass card, as in the Apple TV player.
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = S.safeX - 12.dp, end = S.safeX - 12.dp, bottom = S.safeY - 8.dp)
                    .glass(RoundedCornerShape(16.dp), 1.3f)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
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
                    Txt(clock(position), T.label.copy(fontFeatureSettings = "tnum"))
                    ProgressBar(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f), height = 5.dp)
                    Txt(if (duration > 0) "-" + clock(duration - position) else "--:--", T.label, color = C.inkMuted)
                }
                ControlBar(barItems, bar)
            }
        }

        if (showSkipIntro && !panelOpen) {
            Txt(
                "$skipLabel  ▶", T.label, color = C.focusInk,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = S.safeX, bottom = S.safeY + 110.dp)
                    .background(C.focus, RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 11.dp),
            )
        }

        if (nextPrompt && hasNext && !panelOpen) {
            val next = q[index + 1]
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = S.safeX, bottom = S.safeY + 110.dp)
                    .glass(RoundedCornerShape(S.radiusLg), 1.3f).padding(S.s4),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt("NEXT EPISODE IN $countdown", T.section)
                Txt(next.subtitle ?: next.title, T.title)
                Txt("OK play now  ·  Back keep watching", T.caption, color = C.inkMuted)
            }
        }

        if (panelOpen) {
            val (audio, text) = trackChoices(player, tracks)
            when (panelKind) {
                "audio" -> {
                    val rows = audio.map { c -> PanelRow(c.label, selected = c.selected, onClick = c.apply, detail = c.detail) }
                        .ifEmpty { listOf(PanelRow("Only one audio track", note = true)) }
                    Sheet(listOf(SheetCol("Audio", rows, 1f)), focusCol = 0, onClose = { panelOpen = false },
                        modifier = Modifier.align(Alignment.BottomEnd).width(460.dp))
                }
                "more" -> {
                    val playback = buildList {
                        add(PanelRow("Picture", value = fit.label, onClick = { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size] }, keepOpen = true))
                        if (hasNext) add(PanelRow("Next episode ▸", onClick = { panelOpen = false; nextNow() }))
                    }
                    val introRows = if (!isEpisode) emptyList() else buildList {
                        val sid = item.seriesId
                        val iv = intro
                        if (iv != null) {
                            add(PanelRow("Saved intro ${clock(iv.first)} – ${clock(iv.second)}", note = true))
                            add(PanelRow("Skip automatically", value = if (app.prefs.autoSkipIntro(sid)) "On" else "Off",
                                onClick = { app.prefs.setAutoSkipIntro(sid, !app.prefs.autoSkipIntro(sid)); overlayToken++ }, keepOpen = true))
                            add(PanelRow("Forget this intro", onClick = { app.prefs.forgetIntro(sid); intro = null; notice = "Intro forgotten for this show" }))
                        } else {
                            add(PanelRow(
                                if (app.prefs.introLearning(sid)) "No intro saved. Skip it once with ▶▶ near the start and it's offered on the next episodes."
                                else "Intro learning is off for this show.",
                                note = true,
                            ))
                        }
                        add(PanelRow("Learn intros", value = if (app.prefs.introLearning(sid)) "On" else "Off",
                            onClick = { app.prefs.setIntroLearning(sid, !app.prefs.introLearning(sid)); overlayToken++ }, keepOpen = true))
                    }
                    Sheet(
                        listOfNotNull(SheetCol("Playback", playback, 1f), introRows.takeIf { it.isNotEmpty() }?.let { SheetCol("Intro", it, 1.2f) }),
                        focusCol = 0, onClose = { panelOpen = false },
                        modifier = Modifier.align(Alignment.BottomEnd).width(if (introRows.isEmpty()) 420.dp else 760.dp),
                    )
                }
                else -> {
                    // Languages | that language's subtitles | style, as in Nuvio.
                    val fileTracks = text.drop(1)
                    fun langOf(c: TrackChoice) = c.lang ?: "Unknown"
                    val isArabic = { c: TrackChoice -> langOf(c).startsWith("Arabic", true) || langOf(c) == "العربية" }
                    val langs = LinkedHashSet<String>()
                    if (subOptions.isNotEmpty() || fileTracks.any(isArabic) || subStatus != null) langs += "Arabic"
                    fileTracks.forEach { langs += if (isArabic(it)) "Arabic" else langOf(it) }
                    val current = when {
                        extOn -> "Arabic"
                        else -> fileTracks.firstOrNull { it.selected }?.let { if (isArabic(it)) "Arabic" else langOf(it) } ?: "Off"
                    }
                    val shown = subLang ?: current
                    fun pickFirst(lang: String) {
                        val inFile = fileTracks.filter { if (lang == "Arabic") isArabic(it) else langOf(it) == lang }
                        when {
                            inFile.isNotEmpty() -> { useExternal(false); inFile.first().apply() }
                            lang == "Arabic" && subOptions.isNotEmpty() -> loadSub(subChoice.coerceAtLeast(0))
                        }
                    }
                    val langRows = listOf(
                        PanelRow("Off", selected = current == "Off", onFocus = { subLang = "Off" },
                            onClick = { useExternal(false); text.first().apply() }),
                    ) + langs.map { l ->
                        val n = fileTracks.count { if (l == "Arabic") isArabic(it) else langOf(it) == l } + if (l == "Arabic") subOptions.size else 0
                        PanelRow(l, selected = current == l, value = n.takeIf { it > 0 }?.toString(), onFocus = { subLang = l }, onClick = { pickFirst(l) })
                    }
                    val optionRows = buildList {
                        when (shown) {
                            "Off" -> add(PanelRow("Subtitles are off.", note = true))
                            "Arabic" -> {
                                fileTracks.filter(isArabic).forEach { c ->
                                    add(PanelRow(c.label, detail = "In the file", selected = !extOn && c.selected, onClick = { useExternal(false); c.apply() }))
                                }
                                subOptions.take(10).forEachIndexed { i, o ->
                                    add(PanelRow(o.release.take(42), detail = "SubDL", selected = extOn && subChoice == i, onClick = { loadSub(i) }))
                                }
                                subStatus?.let { add(PanelRow(it, note = true)) }
                            }
                            else -> fileTracks.filter { !isArabic(it) && langOf(it) == shown }.forEach { c ->
                                add(PanelRow(c.label, detail = "In the file", selected = !extOn && c.selected, onClick = { useExternal(false); c.apply() }))
                            }
                        }
                    }
                    val sign = if (subDelay > 0) "+" else ""
                    fun cycle(i: Int, n: Int) = (i + 1) % n
                    val styleRows = buildList {
                        if (extOn) {
                            add(PanelRow("Delay", value = "$sign${"%.2f".format(subDelay / 1000f)} s", note = false, keepOpen = true,
                                onClick = { subDelay = 0; app.prefs.setSubDelay(delayKey, 0) }, detail = "OK resets"))
                            add(PanelRow("◀  Earlier", value = "−0.25 s", onClick = { subDelay -= 250; app.prefs.setSubDelay(delayKey, subDelay) }, keepOpen = true))
                            add(PanelRow("Later  ▶", value = "+0.25 s", onClick = { subDelay += 250; app.prefs.setSubDelay(delayKey, subDelay) }, keepOpen = true))
                        } else add(PanelRow("Delay works on SubDL subtitles.", note = true))
                        add(PanelRow("Size", value = style.sizeLabel, keepOpen = true, onClick = { restyle(style.copy(size = cycle(style.size, 5))) }))
                        add(PanelRow("Bold", value = if (style.bold) "On" else "Off", keepOpen = true, onClick = { restyle(style.copy(bold = !style.bold)) }))
                        add(PanelRow("Colour", value = style.colorLabel, keepOpen = true, onClick = { restyle(style.copy(color = cycle(style.color, dev.k3be.restv.data.SubStyle.COLORS.size))) }))
                        add(PanelRow("Opacity", value = style.opacityLabel, keepOpen = true, onClick = { restyle(style.copy(opacity = cycle(style.opacity, dev.k3be.restv.data.SubStyle.OPACITY.size))) }))
                        add(PanelRow("Background", value = if (style.background) "On" else "Off", keepOpen = true, onClick = { restyle(style.copy(background = !style.background)) }))
                        add(PanelRow("Outline", value = if (style.outline) "On" else "Off", keepOpen = true, onClick = { restyle(style.copy(outline = !style.outline)) }))
                        add(PanelRow("Shadow", value = if (style.shadow) "On" else "Off", keepOpen = true, onClick = { restyle(style.copy(shadow = !style.shadow)) }))
                        add(PanelRow("Reset style", keepOpen = true, onClick = { restyle(dev.k3be.restv.data.SubStyle()) }))
                    }
                    Sheet(
                        listOf(SheetCol("Language", langRows, 0.8f), SheetCol(if (shown == "Off") "Subtitles" else shown, optionRows, 1.3f), SheetCol("Style", styleRows, 1f)),
                        focusCol = 0, onClose = { panelOpen = false },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        notice?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = S.safeY)) }
        if (exitHint) Toast("Press Back again to stop watching", Modifier.align(Alignment.TopCenter).padding(top = S.safeY + 48.dp))
    }
}

private fun langName(code: String?): String? =
    code?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).takeIf { d -> d.isNotBlank() && d != it } ?: it }

private data class TrackChoice(val label: String, val selected: Boolean, val apply: () -> Unit, val lang: String? = null, val detail: String? = null)

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
    text += TrackChoice("Off", textOff || tracks.groups.none { it.type == MC.TRACK_TYPE_TEXT && it.isSelected }, apply = {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true).build()
    })
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
                MC.TRACK_TYPE_AUDIO -> {
                    val f = g.getTrackFormat(i)
                    val lang = langName(f.language)
                    audio += TrackChoice(
                        listOfNotNull(lang ?: f.label ?: "Track ${audio.size + 1}", f.label?.takeIf { it != lang }).joinToString(" · "),
                        g.isTrackSelected(i), apply, lang, dev.k3be.restv.ui.player.audioDetail(f),
                    )
                }
                MC.TRACK_TYPE_TEXT -> {
                    val f = g.getTrackFormat(i)
                    val lang = langName(f.language)
                    text += TrackChoice(f.label?.takeIf { it != lang } ?: lang ?: "Track ${text.size}", !textOff && g.isTrackSelected(i), apply, lang)
                }
            }
        }
    }
    return audio to text
}

internal data class SheetCol(val title: String, val rows: List<PanelRow>, val weight: Float)

/** A floating glass sheet of side-by-side columns; Left/Right moves between them. */
@Composable
internal fun Sheet(columns: List<SheetCol>, focusCol: Int, onClose: () -> Unit, modifier: Modifier) {
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    Row(
        modifier.fillMaxWidth()
            .padding(start = S.safeX - 16.dp, end = S.safeX - 16.dp, bottom = S.safeY - 8.dp)
            .glass(RoundedCornerShape(18.dp), 1.4f)
            .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        columns.forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.width(1.dp).height(250.dp).background(Color.White.copy(alpha = 0.08f)))
            PanelColumn(c.title, c.rows, if (i == focusCol) start else null, onClose, Modifier.weight(c.weight))
        }
    }
}

@Composable
internal fun PanelColumn(title: String, rows: List<PanelRow>, focus: FocusRequester?, onClose: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(title.uppercase(), T.section, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        val target = rows.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: rows.indexOfFirst { !it.note }
        LazyColumn(Modifier.heightIn(max = 250.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(rows.size) { i ->
                val r = rows[i]
                if (r.note) Txt(r.label, T.caption, color = C.inkFaint, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                else FocusRow(
                    modifier = if (focus != null && i == target) Modifier.focusRequester(focus) else Modifier,
                    selected = r.selected,
                    height = if (r.detail != null) 52.dp else 42.dp,
                    onFocused = { r.onFocus?.invoke() },
                    onClick = { r.onClick?.invoke(); if (!r.keepOpen) onClose() },
                ) { f ->
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(if (r.selected) "✓" else "", T.label, color = C.accent, modifier = Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(r.label, T.label, color = if (r.selected || f) C.ink else C.inkMuted, maxLines = 1)
                            r.detail?.let { Txt(it, T.caption, color = C.inkFaint, maxLines = 1) }
                        }
                        r.value?.let { Txt(it, T.label, color = if (f) C.ink else C.inkFaint) }
                    }
                }
            }
        }
    }
}

/** The player's bottom bar: round icon buttons; the focused one turns white and shows its name. */
@Composable
internal fun ControlBar(items: List<BarItem>, selected: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, b ->
            val on = i == selected
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(50))
                    .background(if (on) C.focus else Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = if (on) 14.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                dev.k3be.restv.ui.components.Icon(b.icon, if (on) C.focusInk else C.ink, 18.dp)
                if (on) Txt(b.label, T.label, color = C.focusInk)
            }
        }
        Box(Modifier.weight(1f))
        Txt(if (selected >= 0) "▲ back to seeking" else "◀ ▶ seek  ·  ▲ subtitles  ·  ▼ controls", T.caption, color = C.inkFaint)
    }
}

/** A subtitle line the app draws (SubDL), in the chosen style; the outline is a stroke under the fill. */
@Composable
internal fun SubtitleLine(text: String, style: dev.k3be.restv.data.SubStyle, modifier: Modifier) {
    val base = T.titleLg.copy(
        fontSize = style.sp.sp,
        lineHeight = (style.sp * 1.25f).sp,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center,
        letterSpacing = 0.sp,
    )
    Box(
        modifier.background(Color.Black.copy(alpha = if (style.background) 0.6f else 0f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (style.outline) Txt(text, base.copy(color = Color.Black, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
            width = style.sp / 5f, join = androidx.compose.ui.graphics.StrokeJoin.Round)), maxLines = 3)
        Txt(text, base.copy(color = Color(style.argb), shadow = if (style.shadow) Shadow(Color.Black, androidx.compose.ui.geometry.Offset(1.5f, 2f), 6f) else null), maxLines = 3)
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
