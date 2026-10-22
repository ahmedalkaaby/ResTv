package dev.k3be.restv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.T
import kotlinx.coroutines.launch

/** Launch screen: the wordmark fades and settles in over a soft glow, then the app takes over. */
@Composable
fun Splash() {
    val a = remember { Animatable(0f) }
    val line = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { a.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        line.animateTo(1f, tween(900, delayMillis = 250, easing = FastOutSlowInEasing))
    }
    Box(
        // Solid first: nothing behind may show through while the app loads underneath.
        Modifier.fillMaxSize().background(C.bg000).background(
            Brush.radialGradient(listOf(C.accent.copy(alpha = 0.10f), C.bg000), radius = 900f)
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(
                "Restv",
                T.displayXl.copy(fontSize = 64.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
                color = C.ink,
                modifier = Modifier.graphicsLayer {
                    alpha = a.value
                    val s = 0.92f + 0.08f * a.value
                    scaleX = s; scaleY = s
                },
            )
            Box(Modifier.width(140.dp * line.value).height(3.dp).clip(RoundedCornerShape(50)).background(C.accent))
            Txt("Live  ·  Movies  ·  Series  ·  Matches", T.label, color = C.inkFaint, modifier = Modifier.graphicsLayer { alpha = line.value })
        }
    }
}
