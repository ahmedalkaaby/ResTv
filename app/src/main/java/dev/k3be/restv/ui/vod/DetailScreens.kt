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
import androidx.compose.foundation.layout.fillMaxHeight
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

// ---------------------------------------------------------------- Versions

/** One copy of a title in the picker: "English" / "EN · 4K · MKV · Netflix Movies". */
data class VersionOption(val id: Long, val title: String, val detail: String)

/** "Arabic subtitles", or the provider category ("Netflix Movies") when the name has no language tag. */
fun versionTitle(raw: String, categoryName: String?): String =
    VodNaming.source(raw, categoryName)?.let(VodNaming::sourceDescription)
        ?: categoryName?.let { Naming.split(it).name.trim() }?.takeIf { it.isNotBlank() }
        ?: "Standard"

fun versionDetail(raw: String, quality: Int, ext: String?, categoryName: String?): String =
    listOfNotNull(VodNaming.source(raw, categoryName), Naming.qualityLabel(quality) ?: "SD", ext?.uppercase(), categoryName?.let { Naming.split(it).name.trim() })
        .distinct().joinToString("  ·  ")

/** A calm list on the right: pick the language/quality copy once, it stays picked for this title. */
@Composable
fun VersionPicker(options: List<VersionOption>, current: Long, onPick: (VersionOption) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    androidx.activity.compose.BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier.width(420.dp).fillMaxHeight().background(C.bg100.copy(alpha = 0.98f))
            .padding(start = S.s6, end = S.safeX, top = S.safeY + 8.dp, bottom = S.safeY),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt("Choose a version", T.titleLg)
        Txt("Language and picture quality. Your choice is remembered for this title.", T.caption, color = C.inkFaint, maxLines = 2)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(top = S.s3), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options.size) { k ->
                val o = options[k]
                val selected = o.id == current
                dev.k3be.restv.ui.components.FocusRow(
                    modifier = if (selected || (k == 0 && options.none { it.id == current })) Modifier.focusRequester(first) else Modifier,
                    selected = selected,
                    height = 62.dp,
                    onClick = { onPick(o) },
                ) { _ ->
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Txt(if (selected) "✓" else " ", T.title, color = C.accent, modifier = Modifier.width(18.dp))
                        Column {
                            Txt(o.title, T.title)
                            Txt(o.detail, T.caption, color = C.inkMuted)
                        }
                    }
                }
            }
        }
    }
}

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
    var cats by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var picking by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2_500); toast = null } }
    LaunchedEffect(id) {
        val m = app.db.vod().movie(loginId, id) ?: return@LaunchedEffect
        cats = app.db.vod().categories(loginId, "movie").associate { it.categoryId to it.rawName }
        versions = app.db.vod().movieVersions(loginId, m.titleKey)
            .filter { it.year == null || m.year == null || it.year == m.year }
            .sortedWith(compareByDescending<MovieEntity> { it.quality }.thenBy { it.streamId })
        // The version picked last time wins over whichever copy the poster pointed at.
        app.prefs.chosenVersion("movie", m.titleKey)?.takeIf { c -> versions.any { it.streamId == c } }?.let { chosen = it }
        movie = m
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
        app.prefs.setChosenVersion("movie", m.titleKey, current.streamId)
        onPlay(
            listOf(PlayItem("movie", current.streamId, 0, 0, 0, i?.title ?: m.name,
                listOfNotNull(VodNaming.source(current.rawName)?.let(VodNaming::sourceDescription), Naming.qualityLabel(current.quality), m.year?.toString(), i?.genre)
                    .joinToString(" · ").ifBlank { null },
                i?.backdrop ?: m.poster, i?.ext ?: current.ext, i?.durationMs ?: 0)),
            0, fromStart,
        )
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(i?.backdrop ?: m.poster, widthFraction = 0.8f, heightFraction = 1f)
        Row(Modifier.fillMaxSize().padding(start = S.safeX, end = S.safeX, top = 64.dp), horizontalArrangement = Arrangement.spacedBy(S.s6)) {
            Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(i?.title ?: m.name, T.displayXl, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s2), verticalAlignment = Alignment.CenterVertically) {
                    val meta = listOfNotNull(
                        (i?.year ?: m.year)?.toString(),
                        i?.durationMs?.takeIf { it > 0 }?.let(::duration),
                        i?.genre?.split(',')?.take(3)?.joinToString(", ") { it.trim() },
                        (i?.rating ?: m.rating)?.let { "★ %.1f".format(it) },
                    )
                    Txt(meta.joinToString("  ·  "), T.label, color = C.inkMuted)
                    if (current.quality == 3) Badge("4K", BadgeKind.Uhd)
                }
                Txt(i?.plot ?: if (error != null) "Couldn't load the details: $error" else "", T.bodyLg, color = C.inkMuted, maxLines = 3)
                i?.cast?.let { Txt("Starring  $it", T.caption, color = C.inkFaint, maxLines = 1) }
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
                    if (versions.size > 1) TvButton("Versions (${versions.size})") { picking = true }
                }
                // The copy that plays: language and quality, changeable here only.
                val cat = cats[current.categoryId]
                Txt("Playing:  ${versionTitle(current.rawName, cat)}  ·  ${Naming.qualityLabel(current.quality) ?: "SD"}", T.label, color = C.ink)
            }
        }
        if (picking) VersionPicker(
            options = versions.map { v -> VersionOption(v.streamId, versionTitle(v.rawName, cats[v.categoryId]), versionDetail(v.rawName, v.quality, v.ext, cats[v.categoryId])) },
            current = chosen,
            onPick = { o ->
                chosen = o.id
                app.prefs.setChosenVersion("movie", m.titleKey, o.id)
                picking = false
                toast = "Now playing: ${o.title}"
            },
            onClose = { picking = false },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        toast?.let { dev.k3be.restv.ui.components.Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = S.safeY)) }
    }
}

// ---------------------------------------------------------------- Series

@Composable
fun SeriesDetailScreen(
    session: Session,
    id: Long,
    onPlay: (List<PlayItem>, Int, Boolean) -> Unit,
) {
    // The version picked last time opens directly; switching versions happens on this page.
    val app = LocalContext.current.applicationContext as RestvApp
    var current by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(id) {
        val s = app.db.vod().series(session.login.id, id)
        current = s?.let { app.prefs.chosenVersion("series", it.titleKey) }
            ?.takeIf { c -> app.db.vod().seriesByKeys(session.login.id, listOf(s.titleKey)).any { it.seriesId == c } } ?: id
    }
    val c = current ?: return
    androidx.compose.runtime.key(c) { SeriesDetailBody(session, c, { current = it }, onPlay) }
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
    var cats by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        cats = app.db.vod().categories(loginId, "series").associate { it.categoryId to it.rawName }
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
        s?.let { app.prefs.setChosenVersion("series", it.titleKey, id) }
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
                    s?.let { Badge(versionTitle(it.rawName, cats[it.categoryId]), BadgeKind.Plain) }
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
                if (versions.size > 1) TvButton("Versions (${versions.size})") { picking = true }
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
        if (picking) VersionPicker(
            options = versions.map { v -> VersionOption(v.seriesId, versionTitle(v.rawName, cats[v.categoryId]), versionDetail(v.rawName, 0, null, cats[v.categoryId]).replace("SD  ·  ", "")) },
            current = id,
            onPick = { o ->
                picking = false
                s?.let { app.prefs.setChosenVersion("series", it.titleKey, o.id) }
                if (o.id != id) onSwitch(o.id)
            },
            onClose = { picking = false },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
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
