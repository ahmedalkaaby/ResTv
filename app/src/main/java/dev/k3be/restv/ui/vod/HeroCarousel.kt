package dev.k3be.restv.ui.vod

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.home.MatchBackdrop
import dev.k3be.restv.ui.home.MatchDay
import dev.k3be.restv.ui.home.MatchHero
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.M
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import kotlinx.coroutines.delay

/** One page of the carousel at the top of Home: a title, or your team's match today. */
data class Slide(val card: VodCard? = null, val match: MatchDay? = null) {
    val key: String get() = card?.let { "${it.kind}${it.id}" } ?: "match${match?.match?.id}"
}

/** Featured titles of one kind for the Movies or Series tab: trending, then the newest. */
fun featuredOf(rails: List<Rail>, max: Int = 8): List<Slide> =
    (rails.firstOrNull { it.id.startsWith("trending") }?.cards.orEmpty() + rails.firstOrNull { it.id.startsWith("latest") }?.cards.orEmpty())
        .filter { it.kind == "movie" || it.kind == "series" }.distinctBy { it.id }.take(max).map { Slide(card = it) }

/** Up to eight featured titles: trending first, then the newest, mixing movies and series. */
fun featuredSlides(movieRails: List<Rail>, seriesRails: List<Rail>, match: List<MatchDay>): List<Slide> {
    fun pick(rails: List<Rail>) = (rails.firstOrNull { it.id.startsWith("trending") }?.cards.orEmpty() +
        rails.firstOrNull { it.id.startsWith("latest") }?.cards.orEmpty())
        .filter { it.kind == "movie" || it.kind == "series" }
    val m = pick(movieRails).take(5)
    val s = pick(seriesRails).take(3)
    val mixed = buildList {
        val a = m.iterator(); val b = s.iterator()
        while (a.hasNext() || b.hasNext()) { if (a.hasNext()) add(a.next()); if (a.hasNext()) add(a.next()); if (b.hasNext()) add(b.next()) }
    }
    return match.take(1).map { Slide(match = it) } + mixed.distinctBy { it.kind + it.id }.take(8).map { Slide(card = it) }
}

/** The full-bleed picture behind the carousel; it changes only with the slide, never with focus. */
@Composable
fun CarouselBackdrop(slide: Slide?) {
    Crossfade(targetState = slide, animationSpec = tween(700), label = "carousel-bg") { s ->
        when {
            s?.match != null -> MatchBackdrop(s.match)
            s?.card != null -> {
                val art = rememberArt(s.card)
                Backdrop(art?.backdrop ?: s.card.backdrop ?: s.card.poster.takeIf { s.card.kind == "series" }, widthFraction = 0.82f, heightFraction = 1f)
            }
            else -> {}
        }
    }
}

/**
 * The carousel's text: title logo (or name), a meta line, the plot, View Details, and page dots.
 * ◀ ▶ on the button flip pages; it moves on by itself every 9 seconds.
 */
@Composable
fun CarouselInfo(
    slides: List<Slide>,
    index: Int,
    onIndex: (Int) -> Unit,
    onOpen: (Slide) -> Unit,
    modifier: Modifier = Modifier,
    buttonRequester: FocusRequester = remember { FocusRequester() },
    compact: Boolean = false,
) {
    if (slides.isEmpty()) return
    val i = index.coerceIn(0, slides.lastIndex)
    LaunchedEffect(i, slides.size) {
        delay(9_000)
        onIndex((i + 1) % slides.size)
    }
    Box(modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = slides[i],
            transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(250)) },
            label = "carousel",
            modifier = Modifier.align(Alignment.BottomStart),
        ) { slide ->
            if (slide.match != null) MatchHero(slide.match, Modifier.padding(bottom = 38.dp), hint = false)
            else slide.card?.let { SlideText(it, compact) }
        }
        // View Details and the dots stay put while the text changes.
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = S.safeX, end = S.safeX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) M.buttonScale else 1f, M.focus(), label = "details")
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .focusRequester(buttonRequester)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .height(40.dp)
                    .clip(shape)
                    .background(if (focused) C.focus else C.ink.copy(alpha = 0.92f), shape)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionLeft -> { onIndex((i - 1 + slides.size) % slides.size); true }
                            Key.DirectionRight -> { onIndex((i + 1) % slides.size); true }
                            else -> false
                        }
                    }
                    .remoteClickable(source, { onOpen(slides[i]) })
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(if (slides[i].match != null) "See channels" else "View Details", T.label, color = C.focusInk)
            }
            if (focused) Txt("   ◀ ▶  more", T.caption, color = C.inkFaint)
            Box(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                slides.indices.forEach { d ->
                    val w by animateDpAsState(if (d == i) 22.dp else 6.dp, tween(300), label = "dot")
                    Box(Modifier.size(w, 6.dp).clip(RoundedCornerShape(50)).background(if (d == i) C.ink else C.ink.copy(alpha = 0.45f)))
                }
            }
        }
    }
}

/** Logo or title, then "Series · Drama · 2022", rating chips and the plot. Leaves room for the button row. */
@Composable
private fun SlideText(card: VodCard, compact: Boolean) {
    val art = rememberArt(card)
    val details = rememberTitleDetails(card.kind, card.title, card.year, null)
    Column(
        Modifier.padding(start = S.safeX, bottom = 54.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (art?.logo != null) {
            AsyncImage(
                art.logo, card.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                modifier = Modifier.heightIn(max = if (compact) 48.dp else 70.dp).widthIn(max = 340.dp),
            )
        } else {
            Txt(card.title, if (compact) T.display else T.displayXl, maxLines = if (compact) 1 else 2)
        }
        val genres = details?.genres?.take(2)?.joinToString(" & ") ?: card.genre?.split(',')?.take(2)?.joinToString(" & ") { it.trim() }
        Txt(listOfNotNull(if (card.kind == "series") "Series" else "Movie", genres, card.year?.toString()).joinToString("  ·  "), T.label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val tags = listOfNotNull(details?.certification, details?.status?.takeIf { card.kind == "series" }?.let { if (it.contains("Return", true)) "ONGOING" else it.uppercase() })
            if (tags.isNotEmpty()) Txt(tags.joinToString("  |  "), T.caption.copy(letterSpacing = 0.8.sp), color = C.ink,
                modifier = Modifier.border(1.dp, C.ink.copy(alpha = 0.5f), RoundedCornerShape(S.radiusSm)).padding(horizontal = 7.dp, vertical = 2.dp))
            (details?.score ?: card.rating?.toDouble())?.let { r ->
                Txt("IMDb", T.caption.copy(fontSize = 10.sp), color = Color.Black,
                    modifier = Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
                Txt("%.1f".format(r), T.label, color = Color(0xFFF5C518))
            }
            details?.language?.take(2)?.uppercase()?.let { Txt("·  $it", T.label) }
        }
        if (!compact) (details?.overview ?: card.plot)?.let { Txt(it, T.body, color = C.ink.copy(alpha = 0.88f), maxLines = 2) }
    }
}
