package dev.k3be.restv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.M
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T

@Composable
fun Txt(
    text: String,
    style: TextStyle = T.body,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    val base = if (color != Color.Unspecified) color else style.color
    val onWhite = LocalInk.current == C.focusInk
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = if (onWhite) onWhite(base) else base),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Catches Back before the focus system does. On Android TV the first Back otherwise only clears the
 * highlight (nothing selected, the menu still open); handled here it always does [onBack] at once.
 */
fun Modifier.backKey(onBack: () -> Unit): Modifier = this.onPreviewKeyEvent { ev ->
    if (ev.key == Key.Back || ev.key == Key.Escape) {
        if (ev.type == KeyEventType.KeyUp) onBack()
        true
    } else false
}

private fun isOk(key: Key) = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * Makes an element focusable and clickable with the remote: OK clicks, holding OK long-clicks.
 * Long press fires once, on the first key repeat, and suppresses the click on release.
 */
fun Modifier.remoteClickable(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = composed {
    var longFired by remember { mutableStateOf(false) }
    var downSeen by remember { mutableStateOf(false) }
    this
        .onPreviewKeyEvent { ev ->
            if (!isOk(ev.key)) return@onPreviewKeyEvent false
            when (ev.type) {
                KeyEventType.KeyDown -> {
                    // Only a fresh press counts. Repeats from an OK held on something else (the hold
                    // that just opened this menu) must not press the item that now has focus.
                    if (ev.nativeKeyEvent.repeatCount == 0) downSeen = true
                    if (!downSeen) return@onPreviewKeyEvent true
                    if (ev.nativeKeyEvent.repeatCount == 1 && onLongClick != null) {
                        longFired = true
                        onLongClick()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (downSeen && !longFired) onClick()
                    longFired = false
                    downSeen = false
                    true
                }
                else -> false
            }
        }
        .focusable(interactionSource = interactionSource)
}

/**
 * A list row. Focused: solid white with dark text (Apple TV), a small spring lift.
 * Selected (the current item): a soft white wash. Lifted (being moved): white edge.
 */
@Composable
fun FocusRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    lifted: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    height: Dp = 44.dp,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    if (focused) androidx.compose.runtime.LaunchedEffect(Unit) { onFocused() }
    val shape = RoundedCornerShape(S.radiusMd)
    val lift by animateFloatAsState(if (focused) 1.015f else 1f, M.focus(), label = "row")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .then(
                when {
                    lifted -> Modifier.clip(shape).background(C.bg300, shape).border(2.dp, C.focus, shape)
                    focused -> Modifier.clip(shape).background(C.focus, shape)
                    selected -> Modifier.clip(shape).background(C.accentSoft, shape)
                    else -> Modifier.clip(shape)
                }
            )
            .remoteClickable(source, onClick, onLongClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Text and icons inside read the row's ink, so a focused row's content turns dark by itself.
        androidx.compose.runtime.CompositionLocalProvider(LocalInk provides if (focused && !lifted) C.focusInk else C.ink) {
            content(focused)
        }
    }
}

/** The text colour for content inside a row: dark on a focused (white) row, light otherwise. */
val LocalInk = androidx.compose.runtime.compositionLocalOf { C.ink }

/** Muted text inside a row: grey normally, dark grey on a focused (white) row. */
@Composable
fun rowMuted(focused: Boolean): Color = if (focused) C.focusInk.copy(alpha = 0.65f) else C.inkMuted

enum class Glass { Thin, Regular, Thick }

/**
 * Liquid glass. A see-through fill, light falling from the top edge, and a rim that is bright at
 * the top and fades down the sides. Android TV can't blur what moves behind a view, so the depth
 * comes from tint, light and the artwork itself (blurred once when it loads, where needed).
 */
fun Modifier.glass(
    shape: androidx.compose.ui.graphics.Shape,
    strength: Float = 1f,
    level: Glass = Glass.Thick,
): Modifier {
    val fill = when (level) {
        Glass.Thin -> C.glassThin
        Glass.Regular -> C.glassRegular
        Glass.Thick -> C.glassThick
    }
    return this
        .clip(shape)
        .background(fill.copy(alpha = (fill.alpha * strength).coerceAtMost(1f)), shape)
        .background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.10f * strength), 0.5f to Color.White.copy(alpha = 0.02f * strength), 1f to Color.Transparent,
            ),
            shape,
        )
        .border(
            1.dp,
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(C.rimLight, C.rim.copy(alpha = 0.10f))),
            shape,
        )
}

/** Kept for callers: focus no longer casts anything underneath (no glare). */
@Suppress("UNUSED_PARAMETER")
fun Modifier.focusGlow(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier = this

/**
 * Card focus, Apple TV style: a spring lift, a white rim and a sheen of light across the top-left.
 * Apply before clipping content; [scale] is the animated lift.
 */
fun Modifier.cardFocus(focused: Boolean, scale: Float, shape: androidx.compose.ui.graphics.Shape, sheen: Float): Modifier = this
    .graphicsLayer {
        scaleX = scale; scaleY = scale
        this.shape = shape; clip = true
        // A soft dark lift (never a white glow) for depth.
        shadowElevation = if (focused) 18f else 0f
    }
    .drawWithContent {
        drawContent()
        if (sheen > 0f) {
            drawRect(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.26f * sheen), 0.45f to Color.White.copy(alpha = 0f),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                )
            )
        }
    }
    .border(if (focused) M.focusRim else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.07f), shape)

/** The animated lift and sheen for a card; use with [cardFocus]. */
@Composable
fun rememberCardFocus(focused: Boolean): Pair<Float, Float> {
    val scale by animateFloatAsState(if (focused) M.focusScale else 1f, M.focus(), label = "card")
    val sheen by animateFloatAsState(if (focused) 1f else 0f, tween(260), label = "sheen")
    return scale to sheen
}

/** Buttons: pills. Primary is solid white; others are glass. A focused button is solid white and lifts. */
@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) M.buttonScale else 1f, M.focus(), label = "btn")
    val fg = when {
        focused -> C.focusInk
        !enabled -> C.inkFaint
        primary -> C.accentInk
        else -> C.ink
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(42.dp)
            .then(
                when {
                    focused -> Modifier.clip(shape).background(C.focus, shape)
                    primary && enabled -> Modifier.clip(shape).background(C.accent, shape)
                    else -> Modifier.glass(shape, if (enabled) 1f else 0.5f, Glass.Regular)
                }
            )
            .remoteClickable(source, { if (enabled) onClick() })
            .padding(horizontal = S.s4 + 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.s2),
    ) {
        if (icon != null) Icon(icon, fg, 16.dp)
        Txt(text, T.label, color = fg)
    }
}

enum class BadgeKind { Plain, Outline, Uhd, Live, New, Verified }

@Composable
fun Badge(text: String, kind: BadgeKind = BadgeKind.Outline, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(S.radiusSm)
    val (bg, fg, border) = when (kind) {
        BadgeKind.Plain -> Triple(Color.White.copy(alpha = 0.14f), C.ink, null)
        BadgeKind.Outline -> Triple(Color.Transparent, C.inkMuted, Color.White.copy(alpha = 0.28f))
        BadgeKind.Uhd -> Triple(Color.Transparent, C.uhd, C.uhd)
        BadgeKind.Live -> Triple(C.live, Color.White, null)
        BadgeKind.New -> Triple(C.accent, C.accentInk, null)
        BadgeKind.Verified -> Triple(Color.Transparent, C.success, C.success)
    }
    Box(
        modifier
            .height(22.dp)
            .clip(shape)
            .background(bg, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, T.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = fg) }
}

@Composable
fun QualityBadge(quality: Int) {
    when (quality) {
        3 -> Badge("4K", BadgeKind.Uhd)
        2 -> Badge("FHD")
        1 -> Badge("HD")
        else -> {}
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(C.track)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(C.accent)
        )
    }
}

/** A short message chip; never takes focus. */
@Composable
fun Toast(text: String, modifier: Modifier = Modifier, color: Color = C.ink) {
    Box(
        modifier
            .glass(RoundedCornerShape(50), 1.1f)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) { Txt(text, T.label, color = color) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Txt(text.uppercase(), T.section, modifier = modifier)
}

@Composable
fun Icon(vector: ImageVector, tint: Color, size: Dp = 20.dp) {
    val t = if (LocalInk.current == C.focusInk) onWhite(tint) else tint
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        modifier = Modifier.size(size),
        colorFilter = ColorFilter.tint(t),
    )
}

/** The same meaning, readable on a white (focused) surface. */
fun onWhite(c: Color): Color = when (c) {
    C.inkMuted -> C.focusInk.copy(alpha = 0.66f)
    C.inkFaint -> C.focusInk.copy(alpha = 0.5f)
    C.uhd -> Color(0xFF0A6FA8)
    C.success -> Color(0xFF1A8A3A)
    C.liveText, C.danger -> C.live
    C.warning -> Color(0xFF9A6A00)
    else -> if (c.luminance() > 0.6f) C.focusInk.copy(alpha = c.alpha) else c
}

/** Stroke icons on a 24 grid, matching the design system's icon style. */
object Icons {
    private fun stroke(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun solid(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.White))
            .build()

    val ball = stroke("ball", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 7l4 3 -1.5 4.5h-5L8 10z", "M12 3v4M16 10l4.5 -1.5M14.5 14.5l2.5 4M9.5 14.5l-2.5 4M8 10l-4.5 -1.5")
    val chevronRight = stroke("chevronRight", "M9 6l6 6 -6 6")
    val chevronDown = stroke("chevronDown", "M6 9l6 6 6 -6")
    val audio = stroke("audio", "M4 16v-4a8 8 0 0 1 16 0v4", "M5 14h1.5a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1 -1.5 1.5h-1.5a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1z", "M17.5 14h1.5a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-1.5a1.5 1.5 0 0 1 -1.5 -1.5v-3a1.5 1.5 0 0 1 1.5 -1.5z")
    val search = stroke("search", "M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "M20 20l-4.2 -4.2")
    val live = stroke("live", "M5 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-9a2 2 0 0 1 2 -2z", "M8 3l4 4 4 -4")
    val settings = stroke("settings", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06 .06a2 2 0 1 1 -2.83 2.83l-.06 -.06a1.65 1.65 0 0 0 -1.82 -.33 1.65 1.65 0 0 0 -1 1.51V21a2 2 0 1 1 -4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0 -1.82 .33l-.06 .06a2 2 0 1 1 -2.83 -2.83l.06 -.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0 -1.51 -1H3a2 2 0 1 1 0 -4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0 -.33 -1.82l-.06 -.06a2 2 0 1 1 2.83 -2.83l.06 .06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1 -1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82 -.33l.06 -.06a2 2 0 1 1 2.83 2.83l-.06 .06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0 -1.51 1z")
    val star = solid("star", "M12 3l2.7 5.6 6.1 .9 -4.4 4.3 1 6.1L12 17l-5.4 2.9 1 -6.1L3.2 9.5l6.1 -.9z")
    val pin = stroke("pin", "M9 3h6l-1 6 4 4H6l4 -4zM12 13v8")
    val move = stroke("move", "M8 9l4 -4 4 4M8 15l4 4 4 -4")
    val eyeOff = stroke("eyeoff", "M3 3l18 18", "M10.6 5.1A10 10 0 0 1 12 5c5 0 9 4.5 10 7 -.4 1 -1.2 2.3 -2.4 3.5M6.6 6.6C4.5 8 3 10.2 2 12c1 2.5 5 7 10 7 1.7 0 3.3 -.5 4.6 -1.3")
    val play = solid("play", "M7 4.5v15l12.5 -7.5z")
    val pause = solid("pause", "M6.5 4.5h3.5v15h-3.5zM14 4.5h3.5v15h-3.5z")
    val back10 = stroke("back10", "M4 12a8 8 0 1 0 2.4 -5.7L4 8.5", "M4 4v4.5h4.5", "M10 10v5M13 10h2.5v5H13z")
    val fwd10 = stroke("fwd10", "M20 12a8 8 0 1 1 -2.4 -5.7L20 8.5", "M20 4v4.5h-4.5", "M9 10v5M12 10h2.5v5H12z")
    val subtitles = stroke("subtitles", "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-12a1 1 0 0 1 1 -1z", "M7 13h4M13 13h4M7 16h2M11 16h6")
    val skipNext = stroke("skipNext", "M5 5l9 7 -9 7z", "M18 5v14")
    val aspect = stroke("aspect", "M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-10a1 1 0 0 1 1 -1z", "M7 10v-1.5h2.5M17 14v1.5h-2.5")
    val more = solid("more", "M5 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0 -3zM12 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0 -3zM19 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0 -3z")
    val phone = stroke("phone", "M8 3h8a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-8a1.5 1.5 0 0 1 -1.5 -1.5v-15a1.5 1.5 0 0 1 1.5 -1.5z", "M11 18h2")
    val sync = stroke("sync", "M4 12a8 8 0 0 1 14 -5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1 -14 5.3L4 15M4 20v-5h5")
    val check = stroke("check", "M5 12.5l4.5 4.5L19 7.5")
    val history = stroke("history", "M3 12a9 9 0 1 0 3 -6.7L3 8", "M3 3v5h5M12 7v5l3 2")
    val home = stroke("home", "M4 11l8 -7 8 7", "M6 9.5v10h12v-10", "M10 19.5v-5h4v5")
    val film = stroke("film", "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-12a1 1 0 0 1 1 -1z", "M7 5v14M17 5v14M3 9h4M3 15h4M17 9h4M17 15h4")
    val series = stroke("series", "M4 8h16a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-10a1 1 0 0 1 1 -1z", "M7 4h10", "M10 11.5v5l4 -2.5z")
    val download = stroke("download", "M12 4v12M7 11l5 5 5 -5M5 20h14")
}

/** A small menu for a held-OK press: "Remove from Continue watching", "Open details"… Back closes it. */
@Composable
fun ActionMenu(title: String, actions: List<Pair<String, () -> Unit>>, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        androidx.compose.foundation.layout.Column(
            Modifier.width(380.dp).background(C.bg000.copy(alpha = 0.55f), RoundedCornerShape(S.radiusLg)).glass(RoundedCornerShape(S.radiusLg), 1.2f).padding(S.s4),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt(title, T.title, maxLines = 2, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            actions.forEachIndexed { i, (label, action) ->
                FocusRow(
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    height = 48.dp,
                    onClick = { onClose(); action() },
                ) { f -> Txt(label, T.label, color = if (f) C.focusInk else C.ink, modifier = Modifier.padding(horizontal = 14.dp)) }
            }
        }
    }
}
