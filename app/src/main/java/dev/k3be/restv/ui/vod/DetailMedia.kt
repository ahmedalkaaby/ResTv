package dev.k3be.restv.ui.vod

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.k3be.restv.RestvApp
import dev.k3be.restv.data.EpisodeMeta
import dev.k3be.restv.data.Video
import dev.k3be.restv.data.db.WatchEntity
import dev.k3be.restv.ui.components.ProgressBar
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.cardFocus
import dev.k3be.restv.ui.components.rememberCardFocus
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T

/**
 * The artwork behind a details page, edge to edge, with the deep shadows of Nuvio and Apple TV:
 * dark on the left where the text sits and at the bottom where the rows start.
 */
@Composable
fun DetailBackdrop(url: String?) {
    Box(Modifier.fillMaxSize().background(C.bg000)) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to C.bg000.copy(alpha = 0.95f), 0.3f to C.bg000.copy(alpha = 0.78f), 0.55f to C.bg000.copy(alpha = 0.25f), 0.8f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to C.bg000.copy(alpha = 0.45f), 0.16f to Color.Transparent, 0.45f to Color.Transparent, 0.75f to C.bg000.copy(alpha = 0.8f), 1f to C.bg000)))
        // A soft pool of shadow in the bottom-left corner, under the title and buttons.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            listOf(C.bg000.copy(alpha = 0.7f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY), radius = 1500f)))
    }
}

/** Opens a trailer in the YouTube app, or the browser when it isn't installed. */
fun openTrailer(context: android.content.Context, key: String) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(app) }.onFailure { runCatching { context.startActivity(web) } }
}

/** "Trailers & clips": YouTube thumbnails; OK opens YouTube. */
@Composable
fun TrailersRow(videos: List<Video>) {
    if (videos.isEmpty()) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Trailers & clips", T.title, modifier = Modifier.padding(start = S.safeX))
        KeepInView {
            LazyRow(contentPadding = PaddingValues(horizontal = S.safeX, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(videos.size, key = { videos[it].key }) { i -> TrailerCard(videos[i]) { openTrailer(context, videos[i].key) } }
            }
        }
    }
}

@Composable
private fun TrailerCard(v: Video, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val (scale, sheen) = rememberCardFocus(focused)
    val shape = RoundedCornerShape(S.radiusMd)
    Column(Modifier.width(236.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(236.dp, 133.dp).cardFocus(focused, scale, shape, sheen).background(C.bg200).remoteClickable(source, onClick), contentAlignment = Alignment.Center) {
            AsyncImage(v.thumb, v.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, C.bg000.copy(alpha = 0.55f)))))
            Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                Txt("▶", T.label.copy(fontSize = 15.sp), color = Color.White)
            }
            Txt(v.type.uppercase(), T.caption.copy(fontSize = 9.sp, letterSpacing = 1.sp), color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(7.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Txt(v.name, T.caption, color = if (focused) C.ink else C.inkMuted, maxLines = 2)
    }
}

/** TMDB's episode facts for one season, keyed by episode number. */
@Composable
fun rememberSeasonMeta(tmdbId: String?, season: Int): Map<Int, EpisodeMeta> {
    val app = LocalContext.current.applicationContext as RestvApp
    val state by produceState(emptyMap<Int, EpisodeMeta>(), tmdbId, season) {
        value = emptyMap()
        if (tmdbId == null || season < 0) return@produceState
        value = app.art.season(tmdbId, season).associateBy { it.number }
    }
    return state
}

/** A season's poster with its name; focusing it shows that season's episodes. */
@Composable
fun SeasonPosterCard(name: String, poster: String?, selected: Boolean, done: Int, total: Int, onFocus: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    if (focused) LaunchedEffect(Unit) { onFocus() }
    val (scale, sheen) = rememberCardFocus(focused)
    val shape = RoundedCornerShape(S.radiusMd)
    Column(Modifier.width(92.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(92.dp, 138.dp).cardFocus(focused, scale, shape, sheen)
                .background(Brush.linearGradient(listOf(C.bg300, C.bg100)))
                .border(if (selected && !focused) 2.dp else 0.dp, if (selected && !focused) Color.White.copy(alpha = 0.85f) else Color.Transparent, shape)
                .remoteClickable(source, onFocus),
            contentAlignment = Alignment.Center,
        ) {
            Txt(name.replace("Season", "S").trim(), T.titleLg, color = C.inkMuted)
            if (poster != null) AsyncImage(poster, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (selected || focused) 1f else 0.6f })
            if (total > 0 && done > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(C.track)) {
                Box(Modifier.fillMaxWidth(done.toFloat() / total).height(3.dp).background(Color.White))
            }
        }
        Txt(name, T.caption, color = if (selected || focused) C.ink else C.inkFaint, maxLines = 1)
    }
}

/**
 * A large episode card: the still, "E3 · Title", the synopsis, IMDb-style score, air date and
 * length. TMDB fills what the provider doesn't have.
 */
@Composable
fun EpisodeTile(
    number: Int,
    title: String,
    plot: String?,
    image: String?,
    score: Double?,
    airDate: String?,
    durationMin: Int?,
    watch: WatchEntity?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    requestFocus: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fr = remember { FocusRequester() }
    if (focused) LaunchedEffect(Unit) { onFocus(); onFocused() }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val (scale, sheen) = rememberCardFocus(focused)
    val shape = RoundedCornerShape(S.radiusMd)
    val finished = watch?.finished == true
    Column(Modifier.width(268.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier.size(268.dp, 151.dp).cardFocus(focused, scale, shape, sheen).background(C.bg200)
                .focusRequester(fr).remoteClickable(source, onClick, onLongClick),
        ) {
            if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (finished && !focused) 0.55f else 1f })
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to C.bg000.copy(alpha = 0.7f))))
            Txt("EPISODE $number", T.caption.copy(fontSize = 10.sp, letterSpacing = 1.2.sp), color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
            if (finished) Txt("✓ Watched", T.caption, color = C.success,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(C.scrimStrong, RoundedCornerShape(S.radiusSm)).padding(horizontal = 6.dp))
            durationMin?.let { Txt("${it}m", T.caption, color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp)) }
            if (watch != null && !finished && watch.positionMs > 0) ProgressBar(watch.fraction, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
        Txt(title, T.label, color = if (focused) C.ink else C.ink.copy(alpha = 0.9f), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            score?.let {
                Txt("IMDb", T.caption.copy(fontSize = 9.sp), color = Color.Black,
                    modifier = Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
                Txt("%.1f".format(it), T.caption, color = Color(0xFFF5C518))
            }
            airDate?.let { Txt(prettyAirDate(it), T.caption, color = C.inkFaint) }
        }
        plot?.let { Txt(it, T.caption, color = C.inkMuted, maxLines = 3) }
    }
}

/** "2022-02-04" → "Feb 4, 2022". */
fun prettyAirDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH))
}.getOrDefault(iso)
