package dev.k3be.restv.ui.vod

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.k3be.restv.ui.components.Badge
import dev.k3be.restv.ui.components.BadgeKind
import dev.k3be.restv.ui.components.ProgressBar
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T

val POSTER_W = 104.dp
val POSTER_H = 156.dp

/**
 * Full-screen artwork behind a screen. The whole picture shows; a soft shade on the left keeps the
 * title readable and a fade at the bottom sits under the rows. (The size parameters are kept for
 * callers but the picture always fills the screen.)
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun Backdrop(url: String?, modifier: Modifier = Modifier, widthFraction: Float = 1f, heightFraction: Float = 1f) {
    Box(modifier.fillMaxSize()) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to C.bg000.copy(alpha = 0.88f), 0.38f to C.bg000.copy(alpha = 0.55f), 0.65f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to C.bg000.copy(alpha = 0.35f), 0.18f to Color.Transparent, 0.5f to Color.Transparent, 0.78f to C.bg000.copy(alpha = 0.85f), 1f to C.bg000)
            )
        )
    }
}

val WIDE_W = 236.dp
val WIDE_H = 133.dp

/** Height of one rail of wide cards: header, cards and room for the focus lift. */
val RAIL_H = WIDE_H + 58.dp

/** Artwork for a card: from the disk cache at once, otherwise looked up (TMDB or the provider). */
@Composable
fun rememberArt(card: VodCard?): dev.k3be.restv.data.Art? {
    if (card == null || card.kind == "platform") return null
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as dev.k3be.restv.RestvApp
    // Keyed on the title: when focus moves on, the previous title's artwork must not linger.
    val state = androidx.compose.runtime.produceState<dev.k3be.restv.data.Art?>(
        app.art.peek(card.kind, card.title, card.year), card.kind, card.id,
    ) {
        value = app.art.peek(card.kind, card.title, card.year)
        if (value == null) value = app.art.get(card.kind, card.id, card.title, card.year, card.backdrop)
    }
    return state.value
}

/** Apple TV-style landscape card: artwork, the title's logo (or name) and progress. */
@Composable
fun WideCard(
    card: VodCard,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fr = remember { FocusRequester() }
    if (focused) LaunchedEffect(Unit) { onFocus() }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(90), label = "wide")
    val shape = RoundedCornerShape(S.radiusMd)
    Box(
        modifier
            .size(WIDE_W, WIDE_H)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.shape = shape; clip = true }
            .background(C.bg200)
            .border(2.dp, if (focused) C.focus else Color.Transparent, shape)
            .focusRequester(fr)
            .remoteClickable(source, onClick, onLongClick),
    ) {
        if (card.kind == "platform") { PlatformArt(card.title); return@Box }
        val art = rememberArt(card)
        val img = art?.backdrop ?: card.backdrop
        if (img != null) {
            AsyncImage(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (!card.poster.isNullOrBlank()) {
            // Only a poster: show its upper part, which usually holds the faces and the title.
            AsyncImage(card.poster, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(C.bg300, C.bg100))))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to C.bg000.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = if (card.progress != null) 10.dp else 8.dp)) {
            if (art?.logo != null) {
                AsyncImage(art.logo, null, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                    modifier = Modifier.height(34.dp).fillMaxWidth(0.7f))
            } else {
                Txt(card.title, T.label, color = C.ink, maxLines = 1)
            }
        }
        if (card.note != null) {
            Txt(card.note, T.caption, color = C.ink, modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                .background(C.scrimStrong, RoundedCornerShape(S.radiusSm)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        card.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 4.dp) }
    }
}

/** The five platforms' colours, for their tiles and pages (names are written, no logos). */
fun platformColors(name: String): List<Color> = when (name) {
    "Apple TV+" -> listOf(Color(0xFF2C2C2E), Color(0xFF0E0E10))
    "Netflix" -> listOf(Color(0xFF8E0A12), Color(0xFF1A0204))
    "Disney+" -> listOf(Color(0xFF1C3F9E), Color(0xFF07122E))
    "HBO" -> listOf(Color(0xFF5B2A9E), Color(0xFF140826))
    "OSN+" -> listOf(Color(0xFF0E6E7A), Color(0xFF051E22))
    else -> listOf(C.bg300, C.bg100)
}

@Composable
fun BoxScope.PlatformArt(name: String) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(platformColors(name))), contentAlignment = Alignment.Center) {
        Txt(name, T.displayXl.copy(fontSize = T.title.fontSize.times(1.6f), fontWeight = androidx.compose.ui.text.font.FontWeight.Black), color = Color.White)
    }
}

/** A 2:3 poster. Focus lifts it with a white ring; progress shows along the bottom. */
@Composable
fun PosterCard(
    card: VodCard,
    inList: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = POSTER_W,
    height: Dp = POSTER_H,
    requestFocus: Boolean = false,
    showTitle: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fr = remember { FocusRequester() }
    if (focused) LaunchedEffect(Unit) { onFocus() }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val scale by animateFloatAsState(
        if (focused) 1.07f else 1f,
        tween(90),
        label = "poster",
    )
    val shape = RoundedCornerShape(S.radiusMd)
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(width, height)
                .graphicsLayer { scaleX = scale; scaleY = scale; this.shape = shape; clip = true }
                .background(C.bg200)
                .border(2.dp, if (focused) C.focus else Color.Transparent, shape)
                .focusRequester(fr)
                .remoteClickable(source, onClick, onLongClick),
        ) {
            PosterArt(card)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                if (card.note != null) {
                    Txt(card.note, T.caption, color = C.ink, modifier = Modifier.padding(5.dp).background(C.scrimStrong, RoundedCornerShape(S.radiusSm)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                card.progress?.let { ProgressBar(it, Modifier.fillMaxWidth(), height = 4.dp) }
            }
        }
        if (showTitle) Txt(card.title, T.caption, color = if (focused) C.ink else C.inkMuted)
    }
}

/** Small label on artwork. */
@Composable
fun Chip(text: String, color: Color) {
    Txt(
        text, T.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = color,
        modifier = Modifier.background(C.scrimStrong, RoundedCornerShape(S.radiusSm)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Artwork, or the title on a tinted card when the provider has no poster. */
@Composable
fun BoxScope.PosterArt(card: VodCard) {
    val fallback = @Composable {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.bg300, C.bg100))).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) { Txt(card.title, T.label, color = C.inkMuted, maxLines = 4, modifier = Modifier, ) }
    }
    if (card.poster.isNullOrBlank()) fallback()
    else AsyncImage(
        model = card.poster,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/** One horizontally scrolling rail with a title and an optional "See all" tile at the end. */
@Composable
fun RailRow(
    rail: Rail,
    wishIds: Set<Long>,
    restoreCardId: Long?,
    onFocus: (VodCard) -> Unit,
    onClick: (VodCard) -> Unit,
    onLongClick: (VodCard) -> Unit,
    onSeeAll: (() -> Unit)?,
    start: Dp = S.safeX,
    nextTitle: String? = null,
    showTitles: Boolean = false,
    wide: Boolean = true,
) {
    val cards = rail.cards.orEmpty()
    val state = rememberLazyListState()
    val h = if (wide) RAIL_H else POSTER_H + 58.dp + (if (showTitles) 22.dp else 0.dp)
    Column(Modifier.fillMaxWidth().height(h), verticalArrangement = Arrangement.spacedBy(S.s2)) {
        RailHeader(rail.title, rail.accent, null, start)
        if (cards.isEmpty()) {
            SkeletonRow(start, wide)
            return@Column
        }
        KeepInView { LazyRow(
            state = state,
            contentPadding = PaddingValues(start = start, end = S.safeX, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(cards.size, key = { "${cards[it].kind}${cards[it].id}" }) { i ->
                val c = cards[i]
                if (wide) WideCard(
                    card = c,
                    requestFocus = restoreCardId == c.id,
                    onFocus = { onFocus(c) },
                    onClick = { onClick(c) },
                    onLongClick = { onLongClick(c) },
                ) else PosterCard(
                    card = c,
                    inList = c.id in wishIds,
                    requestFocus = restoreCardId == c.id,
                    showTitle = showTitles,
                    onFocus = { onFocus(c) },
                    onClick = { onClick(c) },
                    onLongClick = { onLongClick(c) },
                )
            }
            if (onSeeAll != null) item(key = "all") { SeeAllTile(onSeeAll) }
        } }
    }
}

@Composable
fun RailHeader(title: String, accent: Boolean, nextTitle: String?, start: Dp) {
    Row(Modifier.fillMaxWidth().padding(start = start, end = S.safeX), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, T.title, color = if (accent) C.accent else C.ink, modifier = Modifier.weight(1f))
        if (nextTitle != null) Txt("▾  $nextTitle", T.caption, color = C.inkFaint)
    }
}

@Composable
private fun SeeAllTile(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(S.radiusMd)
    Box(
        Modifier
            .size(WIDE_H, WIDE_H)
            .clip(shape)
            .background(if (focused) C.focus else C.bg200)
            .border(1.dp, C.line, shape)
            .remoteClickable(source, onClick),
        contentAlignment = Alignment.Center,
    ) { Txt("See all ›", T.label, color = if (focused) C.focusInk else C.inkMuted, modifier = Modifier, maxLines = 1) }
}

/** Title, one line of facts, small badges and two lines of plot: nothing else competes with the artwork. */
@Composable
fun HeroInfo(hero: Hero?, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = S.safeX, end = S.safeX, bottom = S.s4).width(540.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hero == null) return@Column
        val art = rememberArt(hero.card)
        if (art?.logo != null) {
            AsyncImage(art.logo, hero.card.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                modifier = Modifier.height(92.dp).fillMaxWidth(0.75f))
        } else {
            Txt(hero.card.title, T.display.copy(fontSize = T.display.fontSize.times(1.1f), fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hero.meta.isNotBlank()) Txt(hero.meta, T.label, color = C.inkMuted)
            if (hero.card.quality == 3) Chip("4K", C.uhd)
            val sources = listOfNotNull(hero.card.source) + hero.card.otherSources
            if (sources.isNotEmpty()) Chip(sources.joinToString(" · "), C.ink)
        }
        hero.plot?.let { Txt(it, T.body, color = C.inkMuted, maxLines = 2) }
    }
}

/** Artwork behind the screen for the focused title. Its own composable, so only it redraws on focus. */
@Composable
fun HeroBackdrop(url: String?) {
    androidx.compose.animation.Crossfade(targetState = url, animationSpec = tween(180), label = "backdrop") { u -> Backdrop(u) }
}

/** Placeholder posters that pulse while a rail loads. */
@Composable
private fun SkeletonRow(start: Dp, wide: Boolean = true) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(
        0.35f, 0.7f,
        androidx.compose.animation.core.infiniteRepeatable(tween(800), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "a",
    )
    Row(Modifier.padding(start = start, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(8) {
            Box(Modifier.size(if (wide) WIDE_W else POSTER_W, if (wide) WIDE_H else POSTER_H).graphicsLayer { alpha = a }.clip(RoundedCornerShape(S.radiusMd)).background(C.bg200))
        }
    }
}
