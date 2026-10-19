package dev.k3be.restv.ui.vod

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.k3be.restv.PlayItem
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.data.Episode
import dev.k3be.restv.data.MovieInfo
import dev.k3be.restv.data.Naming
import dev.k3be.restv.data.VodNaming
import dev.k3be.restv.data.SeriesInfo
import dev.k3be.restv.data.db.MovieEntity
import dev.k3be.restv.data.db.SeriesEntity
import dev.k3be.restv.data.db.WatchEntity
import dev.k3be.restv.data.db.WishEntity
import dev.k3be.restv.ui.components.Badge
import dev.k3be.restv.ui.components.BadgeKind
import dev.k3be.restv.ui.components.Icons
import dev.k3be.restv.ui.components.ProgressBar
import dev.k3be.restv.ui.components.TvButton
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Movie

@Composable
fun MovieDetailScreen(
    session: Session,
    id: Long,
    onPlay: (List<PlayItem>, Int, Boolean) -> Unit,
) {
    val app = LocalContext.current.applicationContext as RestvApp
    val scope = rememberCoroutineScope()
    val accountId = session.account.id
    val loginId = session.login.id
    var movie by remember { mutableStateOf<MovieEntity?>(null) }
    var versions by remember { mutableStateOf<List<MovieEntity>>(emptyList()) }
    var chosen by remember { mutableStateOf(id) }
    var info by remember { mutableStateOf<MovieInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        val m = app.db.vod().movie(loginId, id) ?: return@LaunchedEffect
        movie = m
        versions = app.db.vod().movieVersions(loginId, m.titleKey)
            .filter { it.year == null || m.year == null || it.year == m.year }
            .sortedWith(compareByDescending<MovieEntity> { it.streamId == id }.thenByDescending { it.quality })
    }
    LaunchedEffect(chosen) {
        info = null
        error = null
        info = runCatching { app.vod.movieInfo(session.login, chosen) }.onFailure { error = it.message }.getOrNull()
    }
    val watch by remember(chosen) { app.db.watch().observe(accountId, loginId, "movie", chosen) }.collectAsState(null)
    val wish by remember { app.db.wish().observe(accountId, loginId) }.collectAsState(emptyList<WishEntity>())
    val inList = wish.any { it.kind == "movie" && it.itemId == id }
    val primary = remember { FocusRequester() }
    LaunchedEffect(movie) { if (movie != null) runCatching { primary.requestFocus() } }

    val m = movie ?: return
    val current = versions.firstOrNull { it.streamId == chosen } ?: m
    val i = info
    fun play(fromStart: Boolean) {
        onPlay(
            listOf(PlayItem("movie", current.streamId, 0, 0, 0, i?.title ?: m.name,
                listOfNotNull(VodNaming.source(current.rawName)?.let(VodNaming::sourceDescription), Naming.qualityLabel(current.quality), m.year?.toString(), i?.genre)
                    .joinToString(" · ").ifBlank { null },
                i?.backdrop ?: m.poster, i?.ext ?: current.ext, i?.durationMs ?: 0)),
            0, fromStart,
        )
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(i?.backdrop ?: m.poster, widthFraction = 0.78f, heightFraction = 0.9f)
        Row(Modifier.fillMaxSize().padding(start = S.safeX, end = S.safeX, top = S.safeY + 8.dp), horizontalArrangement = Arrangement.spacedBy(S.s6)) {
            Box(Modifier.size(170.dp, 255.dp).clip(RoundedCornerShape(S.radiusMd)).background(C.bg200)) {
                PosterArt(VodCard("movie", m.streamId, m.name, m.year, i?.poster ?: m.poster))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(i?.title ?: m.name, T.display, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s2), verticalAlignment = Alignment.CenterVertically) {
                    val meta = listOfNotNull(
                        (i?.year ?: m.year)?.toString(),
                        i?.durationMs?.takeIf { it > 0 }?.let(::duration),
                        i?.genre?.split(',')?.take(3)?.joinToString(", ") { it.trim() },
                        (i?.rating ?: m.rating)?.let { "★ %.1f".format(it) },
                    )
                    Txt(meta.joinToString("  ·  "), T.label, color = C.accent)
                    if (current.quality == 3) Badge("4K", BadgeKind.Uhd)
                    VodNaming.source(current.rawName)?.let { Badge(VodNaming.sourceDescription(it), BadgeKind.Plain) }
                }
                Txt(i?.plot ?: if (error != null) "Couldn't load the details: $error" else "", T.body, color = C.inkMuted, maxLines = 4, modifier = Modifier.width(560.dp))
                listOfNotNull(i?.director?.let { "Director  $it" }, i?.cast?.let { "Cast  $it" }).forEach {
                    Txt(it, T.caption, color = C.inkFaint, maxLines = 1, modifier = Modifier.width(560.dp))
                }
                val w = watch
                val started = w != null && w.positionMs > 30_000 && !w.finished
                if (started && w != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s3)) {
                        ProgressBar(w.fraction, Modifier.width(220.dp))
                        Txt(left(w) ?: "", T.caption, color = C.inkMuted)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                    TvButton(if (started) "Resume" else if (w?.finished == true) "Play again" else "Play", primary = true, icon = Icons.play, modifier = Modifier.focusRequester(primary)) { play(fromStart = !started) }
                    if (started) TvButton("Start over", icon = Icons.history) { play(fromStart = true) }
                    TvButton(if (inList) "In My List" else "My List", icon = if (inList) Icons.check else Icons.star) {
                        scope.launch {
                            if (inList) app.db.wish().remove(accountId, loginId, "movie", id)
                            else app.db.wish().add(WishEntity(accountId, loginId, "movie", id, System.currentTimeMillis()))
                        }
                    }
                }
                if (versions.size > 1) {
                    Txt("VERSIONS", T.section, modifier = Modifier.padding(top = 6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                        items(versions.size) { k ->
                            val v = versions[k]
                            val src = VodNaming.source(v.rawName)
                            val label = listOfNotNull(src?.let { "$it · ${VodNaming.sourceDescription(it)}" } ?: "Other", Naming.qualityLabel(v.quality)).joinToString(" · ")
                            TvButton((if (v.streamId == chosen) "✓ " else "") + label, primary = v.streamId == chosen) { chosen = v.streamId }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Series

@Composable
fun SeriesDetailScreen(
    session: Session,
    id: Long,
    onPlay: (List<PlayItem>, Int, Boolean) -> Unit,
) {
    // Other language versions of the same show switch in place.
    var current by remember { mutableStateOf(id) }
    androidx.compose.runtime.key(current) { SeriesDetailBody(session, current, { current = it }, onPlay) }
}

@Composable
private fun SeriesDetailBody(
    session: Session,
    id: Long,
    onSwitch: (Long) -> Unit,
    onPlay: (List<PlayItem>, Int, Boolean) -> Unit,
) {
    val app = LocalContext.current.applicationContext as RestvApp
    val scope = rememberCoroutineScope()
    val accountId = session.account.id
    val loginId = session.login.id
    var series by remember { mutableStateOf<SeriesEntity?>(null) }
    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var versions by remember { mutableStateOf<List<SeriesEntity>>(emptyList()) }
    LaunchedEffect(id) {
        series = app.db.vod().series(loginId, id)
        series?.let { s0 ->
            versions = app.db.vod().seriesByKeys(loginId, listOf(s0.titleKey))
                .filter { it.year == null || s0.year == null || kotlin.math.abs(it.year - s0.year) <= 1 }
                .sortedByDescending { it.seriesId == id }
        }
        info = runCatching { app.vod.seriesInfo(session.login, id) }.onFailure { error = it.message }.getOrNull()
    }
    val watched by remember { app.db.watch().observeSeries(accountId, loginId, id) }.collectAsState(emptyList<WatchEntity>())
    val byEpisode = watched.associateBy { it.itemId }
    val wish by remember { app.db.wish().observe(accountId, loginId) }.collectAsState(emptyList<WishEntity>())
    val inList = wish.any { it.kind == "series" && it.itemId == id }

    val s = series
    val i = info
    val all: List<Episode> = i?.seasons?.flatMap { it.episodes }.orEmpty()
    // Where to pick up: the last episode touched, or the one after it when that one is finished.
    val last = watched.maxByOrNull { it.updatedAt }
    val resumeIndex = last?.let { w ->
        val k = all.indexOfFirst { it.id == w.itemId }
        when {
            k < 0 -> 0
            w.finished && k + 1 < all.size -> k + 1
            else -> k
        }
    } ?: 0
    val resumeEp = all.getOrNull(resumeIndex)
    var season by remember { mutableIntStateOf(-1) }
    LaunchedEffect(resumeEp?.season, i) { if (season == -1 && resumeEp != null) season = resumeEp.season }
    var focusedEp by remember { mutableStateOf<Episode?>(null) }
    val primary = remember { FocusRequester() }
    LaunchedEffect(i != null) { if (i != null) runCatching { primary.requestFocus() } }

    val title = i?.title ?: s?.name ?: ""
    fun queue(): List<PlayItem> = all.map { e ->
        PlayItem("episode", e.id, id, e.season, e.number, title,
            listOfNotNull("S${e.season}:E${e.number} · ${e.title}", s?.let { VodNaming.source(it.rawName) }?.let(VodNaming::sourceDescription)).joinToString(" · "),
            e.image ?: i?.backdrop ?: s?.backdrop ?: s?.poster, e.ext, e.durationMs)
    }
    fun play(index: Int) {
        val e = all.getOrNull(index) ?: return
        val w = byEpisode[e.id]
        onPlay(queue(), index, w == null || w.finished)
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(i?.backdrop ?: s?.backdrop ?: s?.poster, widthFraction = 0.78f, heightFraction = 0.85f)
        Column(Modifier.fillMaxSize().padding(top = S.safeY + 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.padding(start = S.safeX).width(600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(title, T.display, maxLines = 1)
                val meta = listOfNotNull(
                    (i?.year ?: s?.year)?.toString(),
                    i?.seasons?.size?.let { "$it season${if (it == 1) "" else "s"}" },
                    (i?.genre ?: s?.genre)?.split(',')?.take(3)?.joinToString(", ") { it.trim() },
                    (i?.rating ?: s?.rating)?.let { "★ %.1f".format(it) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(meta.joinToString("  ·  "), T.label, color = C.accent)
                    s?.let { VodNaming.source(it.rawName) }?.let { Badge(VodNaming.sourceDescription(it), BadgeKind.Plain) }
                }
                Txt(i?.plot ?: s?.plot ?: if (error != null) "Couldn't load the episodes: $error" else "Loading…", T.body, color = C.inkMuted, maxLines = 3)
            }
            Row(Modifier.padding(start = S.safeX), horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                val w = resumeEp?.let { byEpisode[it.id] }
                val label = when {
                    resumeEp == null -> "Play"
                    w != null && !w.finished -> "Resume S${resumeEp.season}:E${resumeEp.number}" + (left(w)?.let { " · $it" } ?: "")
                    last != null -> "Next  S${resumeEp.season}:E${resumeEp.number}"
                    else -> "Play  S${resumeEp.season}:E${resumeEp.number}"
                }
                TvButton(label, primary = true, icon = Icons.play, enabled = resumeEp != null, modifier = Modifier.focusRequester(primary)) { play(resumeIndex) }
                TvButton(if (inList) "In My List" else "My List", icon = if (inList) Icons.check else Icons.star) {
                    scope.launch {
                        if (inList) app.db.wish().remove(accountId, loginId, "series", id)
                        else app.db.wish().add(WishEntity(accountId, loginId, "series", id, System.currentTimeMillis()))
                    }
                }
                // Other versions of this show (EN, AR, AR SUBS…): one press switches.
                versions.filter { it.seriesId != id }.forEach { v ->
                    TvButton(VodNaming.source(v.rawName)?.let { "$it version" } ?: "Other version") { onSwitch(v.seriesId) }
                }
            }
            if (i != null && i.seasons.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = S.safeX), horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                    items(i.seasons.size) { k ->
                        val se = i.seasons[k]
                        val done = se.episodes.count { byEpisode[it.id]?.finished == true }
                        SeasonChip(
                            label = se.name + if (done > 0) "  $done/${se.episodes.size}" else "",
                            selected = se.number == season,
                            onFocus = { season = se.number },
                        )
                    }
                }
                val eps = i.seasons.firstOrNull { it.number == season }?.episodes ?: i.seasons.first().episodes
                val rowState = rememberLazyListState()
                LaunchedEffect(season) {
                    val k = eps.indexOfFirst { it.id == resumeEp?.id }
                    rowState.scrollToItem(if (k > 0) k else 0)
                }
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = S.safeX),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(eps.size, key = { eps[it].id }) { k ->
                        val e = eps[k]
                        EpisodeCard(
                            e = e,
                            watch = byEpisode[e.id],
                            fallbackImage = i.backdrop ?: s?.backdrop,
                            onFocus = { focusedEp = e },
                            onClick = { play(all.indexOf(e)) },
                            onLongClick = {
                                // Hold OK: mark watched / unwatched.
                                scope.launch {
                                    val w = byEpisode[e.id]
                                    val dur = e.durationMs.takeIf { it > 0 } ?: w?.durationMs?.takeIf { it > 0 } ?: 1
                                    val finished = w?.finished == true
                                    app.db.watch().upsert(
                                        WatchEntity(accountId, loginId, "episode", e.id, id, e.season, e.number, title,
                                            "S${e.season}:E${e.number} · ${e.title}", e.image, e.ext,
                                            if (finished) 0 else dur, dur, System.currentTimeMillis())
                                    )
                                }
                            },
                        )
                    }
                }
                val fe = focusedEp
                Txt(
                    fe?.let { "E${it.number} · ${it.title}" + (it.plot?.let { p -> " — $p" } ?: "") } ?: "Hold OK on an episode to mark it watched.",
                    T.caption, color = C.inkMuted, maxLines = 2, modifier = Modifier.padding(horizontal = S.safeX),
                )
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onFocus: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    if (focused) LaunchedEffect(Unit) { onFocus() }
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> C.focus
                    selected -> C.bg300
                    else -> C.bg200.copy(alpha = 0.7f)
                }
            )
            .remoteClickable(source, onFocus)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(label, T.label, color = if (focused) C.focusInk else if (selected) C.ink else C.inkMuted) }
}

@Composable
private fun EpisodeCard(
    e: Episode,
    watch: WatchEntity?,
    fallbackImage: String?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    if (focused) LaunchedEffect(Unit) { onFocus() }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "ep")
    val shape = RoundedCornerShape(S.radiusMd)
    val finished = watch?.finished == true
    Column(Modifier.width(208.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(208.dp, 117.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(C.bg200)
                .then(if (focused) Modifier.border(2.dp, C.focus, shape) else Modifier)
                .remoteClickable(source, onClick, onLongClick),
        ) {
            val img = e.image ?: fallbackImage
            if (img != null) AsyncImage(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (finished && !focused) 0.55f else 1f })
            Txt("E${e.number}", T.label, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(C.scrimStrong, RoundedCornerShape(S.radiusSm)).padding(horizontal = 6.dp))
            if (finished) Txt("✓ Watched", T.caption, color = C.success, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(C.scrimStrong, RoundedCornerShape(S.radiusSm)).padding(horizontal = 6.dp))
            if (watch != null && !finished && watch.positionMs > 0) ProgressBar(watch.fraction, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
        Txt(e.title, T.label, color = if (focused) C.ink else C.inkMuted)
        val dur = e.durationMs.takeIf { it > 0 }?.let(::duration)
        Txt(
            when {
                finished -> listOfNotNull(dur, "watched").joinToString(" · ")
                watch != null && watch.positionMs > 0 -> left(watch) ?: ""
                else -> dur ?: ""
            },
            T.caption, color = C.inkFaint,
        )
    }
}
