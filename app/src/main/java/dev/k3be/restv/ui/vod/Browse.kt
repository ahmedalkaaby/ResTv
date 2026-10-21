package dev.k3be.restv.ui.vod

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How much of the next row's title shows under the focused row. */
val PEEK = 34.dp

private val glide: AnimationSpec<Float> = tween(320, easing = FastOutSlowInEasing)

/**
 * Vertical: the focused row always slides into the same slot (its posters' top at [pivotPx]),
 * like the Apple TV and Google TV home screens. No half-visible rows, no jumps.
 */
@OptIn(ExperimentalFoundationApi::class)
private class PivotSpec(private val pivotPx: Float) : BringIntoViewSpec {
    @Deprecated("Animation spec is part of BringIntoViewSpec in this Compose version")
    override val scrollAnimationSpec: AnimationSpec<Float> get() = glide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - pivotPx
}

/** Horizontal: only scroll when the focused poster would leave the screen, keeping a margin. */
@OptIn(ExperimentalFoundationApi::class)
private class KeepInViewSpec(private val leadPx: Float, private val trailPx: Float) : BringIntoViewSpec {
    @Deprecated("Animation spec is part of BringIntoViewSpec in this Compose version")
    override val scrollAnimationSpec: AnimationSpec<Float> get() = glide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < leadPx -> offset - leadPx
        offset + size > containerSize - trailPx -> (offset + size) - (containerSize - trailPx)
        else -> 0f
    }
}

/** Rows under the hero: one row in view, the next row's title peeking below. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseRows(
    count: Int,
    key: (Int) -> Any,
    state: LazyListState = rememberLazyListState(),
    rowHeight: Dp = RAIL_H,
    content: @Composable LazyItemScope.(Int) -> Unit,
) {
    val density = LocalDensity.current
    // Posters sit under the row title (≈ 38 dp): that's where the focused row's posters lock.
    val spec = remember(density) { PivotSpec(with(density) { 38.dp.toPx() }) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyColumn(
            state = state,
            userScrollEnabled = false,
            contentPadding = PaddingValues(bottom = rowHeight),
            modifier = Modifier.fillMaxWidth().height(rowHeight + PEEK),
        ) {
            items(count, key = key) { i -> content(i) }
        }
    }
}

/** Wraps a horizontal row so focus moves without the row jumping to center each poster. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeepInView(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density) { KeepInViewSpec(with(density) { 48.dp.toPx() }, with(density) { 120.dp.toPx() }) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) { content() }
}
