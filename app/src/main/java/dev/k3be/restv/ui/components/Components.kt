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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
    BasicText(
        text = text,
        modifier = modifier,
        style = if (color != Color.Unspecified) style.copy(color = color) else style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
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
                    downSeen = true
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

/** A list row: bg-300 fill + white ring when focused, never scales (see the design system). */
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
    val bg = when {
        lifted -> C.bg300
        focused -> C.bg300
        selected -> C.accentSoft
        else -> Color.Transparent
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = if (lifted) 0.dp else 0.dp)
            .clip(shape)
            .background(bg, shape)
            .then(
                when {
                    lifted -> Modifier.border(2.dp, C.accent, shape)
                    focused -> Modifier.border(2.dp, C.focus, shape)
                    else -> Modifier
                }
            )
            .remoteClickable(source, onClick, onLongClick),
        contentAlignment = Alignment.CenterStart,
    ) { content(focused) }
}

/** Buttons: secondary bg-300, primary amber; any focused button becomes a white fill. */
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
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(150), label = "btn")
    val bg = when {
        focused -> C.focus
        !enabled -> C.bg200
        primary -> C.accent
        else -> C.bg300
    }
    val fg = when {
        focused -> C.focusInk
        !enabled -> C.inkFaint
        primary -> C.accentInk
        else -> C.ink
    }
    Row(
        modifier
            .scale(scale)
            .height(40.dp)
            .clip(RoundedCornerShape(S.radiusMd))
            .background(bg)
            .remoteClickable(source, { if (enabled) onClick() })
            .padding(horizontal = S.s4),
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
        BadgeKind.Plain -> Triple(C.bg300, C.ink, null)
        BadgeKind.Outline -> Triple(Color.Transparent, C.inkMuted, C.line)
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
            .then(if (border != null) Modifier.border(1.5.dp, border, shape) else Modifier)
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
            .clip(RoundedCornerShape(50))
            .background(C.bg100.copy(alpha = 0.95f))
            .border(1.dp, C.line, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) { Txt(text, T.label, color = color) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Txt(text.uppercase(), T.section, modifier = modifier)
}

@Composable
fun Icon(vector: ImageVector, tint: Color, size: Dp = 20.dp) {
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        modifier = Modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
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

    val live = stroke("live", "M5 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-9a2 2 0 0 1 2 -2z", "M8 3l4 4 4 -4")
    val settings = stroke("settings", "M8.5 12a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0", "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1l2.1 -2.1M17 7l2.1 -2.1")
    val star = solid("star", "M12 3l2.7 5.6 6.1 .9 -4.4 4.3 1 6.1L12 17l-5.4 2.9 1 -6.1L3.2 9.5l6.1 -.9z")
    val pin = stroke("pin", "M9 3h6l-1 6 4 4H6l4 -4zM12 13v8")
    val move = stroke("move", "M8 9l4 -4 4 4M8 15l4 4 4 -4")
    val eyeOff = stroke("eyeoff", "M3 3l18 18", "M10.6 5.1A10 10 0 0 1 12 5c5 0 9 4.5 10 7 -.4 1 -1.2 2.3 -2.4 3.5M6.6 6.6C4.5 8 3 10.2 2 12c1 2.5 5 7 10 7 1.7 0 3.3 -.5 4.6 -1.3")
    val play = solid("play", "M7 4.5v15l12.5 -7.5z")
    val sync = stroke("sync", "M4 12a8 8 0 0 1 14 -5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1 -14 5.3L4 15M4 20v-5h5")
    val check = stroke("check", "M5 12.5l4.5 4.5L19 7.5")
    val history = stroke("history", "M3 12a9 9 0 1 0 3 -6.7L3 8", "M3 3v5h5M12 7v5l3 2")
    val download = stroke("download", "M12 4v12M7 11l5 5 5 -5M5 20h14")
}
