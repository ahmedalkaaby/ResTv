package dev.k3be.restv.ui.vod

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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

/** Full-bleed artwork behind a screen, fading into the background on the left and bottom. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier, widthFraction: Float = 0.72f, heightFraction: Float = 0.7f) {
    Box(modifier.fillMaxSize()) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(widthFraction).fillMaxSize(heightFraction),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to C.bg000, 0.32f to C.bg000.copy(alpha = 0.92f), 0.62f to C.bg000.copy(alpha = 0.35f), 1f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.45f to C.bg000.copy(alpha = 0.55f), 0.72f to C.bg000)
            )
        )
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
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fr = remember { FocusRequester() }
    if (focused) LaunchedEffect(Unit) { onFocus() }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "poster")
    val shape = RoundedCornerShape(S.radiusMd)
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(width, height)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(C.bg200)
                .then(if (focused) Modifier.border(2.dp, C.focus, shape) else Modifier)
                .focusRequester(fr)
                .remoteClickable(source, onClick, onLongClick),
        ) {
            PosterArt(card)
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (card.quality == 3) Badge("4K", BadgeKind.Uhd, Modifier.background(C.scrimStrong, RoundedCornerShape(S.radiusSm)))
            }
            if (inList) Txt("✓", T.label, color = C.accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(C.scrimStrong, RoundedCornerShape(50)).padding(horizontal = 6.dp))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                if (card.note != null) {
                    Txt(card.note, T.caption, color = C.ink, modifier = Modifier.padding(6.dp).background(C.scrimStrong, RoundedCornerShape(S.radiusSm)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                card.progress?.let { ProgressBar(it, Modifier.fillMaxWidth(), height = 4.dp) }
            }
        }
        Txt(card.title, T.caption, color = if (focused) C.ink else C.inkMuted)
    }
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
) {
    val cards = rail.cards.orEmpty()
    val state = rememberLazyListState()
    Column(Modifier.fillMaxWidth().padding(bottom = S.s3), verticalArrangement = Arrangement.spacedBy(S.s2)) {
        Txt(rail.title, T.title.copy(fontSize = T.label.fontSize.times(1.15f)), color = if (rail.accent) C.accent else C.ink, modifier = Modifier.padding(start = start))
        if (cards.isEmpty()) {
            Box(Modifier.height(POSTER_H + 24.dp).padding(start = start), contentAlignment = Alignment.CenterStart) {
                Txt("Loading…", T.caption, color = C.inkFaint)
            }
            return@Column
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = start, end = S.safeX),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().height(POSTER_H + 30.dp),
        ) {
            items(cards.size, key = { "${cards[it].kind}${cards[it].id}" }) { i ->
                val c = cards[i]
                PosterCard(
                    card = c,
                    inList = c.id in wishIds,
                    requestFocus = restoreCardId == c.id,
                    onFocus = { onFocus(c) },
                    onClick = { onClick(c) },
                    onLongClick = { onLongClick(c) },
                )
            }
            if (onSeeAll != null) item(key = "all") { SeeAllTile(onSeeAll) }
        }
    }
}

@Composable
private fun SeeAllTile(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(S.radiusMd)
    Box(
        Modifier
            .size(POSTER_W, POSTER_H)
            .clip(shape)
            .background(if (focused) C.focus else C.bg200)
            .border(1.dp, C.line, shape)
            .remoteClickable(source, onClick),
        contentAlignment = Alignment.Center,
    ) { Txt("See all ›", T.label, color = if (focused) C.focusInk else C.inkMuted, modifier = Modifier, maxLines = 1) }
}

/** Title, facts and plot for the focused poster, above the rails. */
@Composable
fun HeroText(hero: Hero?, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = S.safeX, end = S.safeX).width(520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hero == null) return@Column
        Txt(hero.card.title, T.display, maxLines = 1)
        if (hero.meta.isNotBlank()) Txt(hero.meta, T.label, color = C.accent)
        hero.plot?.let { Txt(it, T.body, color = C.inkMuted, maxLines = 3) }
    }
}
