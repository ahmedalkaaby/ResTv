package dev.k3be.restv.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.k3be.restv.R

/**
 * Restv Liquid Glass. The interface is white glass on near-black; colour comes from the artwork.
 * 1 dp = 1 px of a 960 × 540 canvas (the mockups are drawn at 1280 × 720: multiply by 0.75).
 */
object C {
    val bg000 = Color(0xFF07080C)
    val bg100 = Color(0xFF101117)
    val bg200 = Color(0xFF17181F)
    val bg300 = Color(0xFF22232B)
    val line = Color(0x24FFFFFF)
    val ink = Color(0xFFF5F5F7)
    val inkMuted = Color(0xFFB4B5BC)
    val inkFaint = Color(0xFF80818A)
    /** The primary action and the selected state: white, as on Apple TV. */
    val accent = Color(0xFFFFFFFF)
    val accentInk = Color(0xFF0B0B0F)
    val accentSoft = Color(0x24FFFFFF)
    val focus = Color(0xFFFFFFFF)
    val focusInk = Color(0xFF0B0B0F)
    val live = Color(0xFFFF453A)
    val liveText = Color(0xFFFF6961)
    val uhd = Color(0xFF64D2FF)
    val success = Color(0xFF30D158)
    val warning = Color(0xFFFFD60A)
    val danger = Color(0xFFFF6961)
    val scrimStrong = Color(0xE607080C)
    val scrimSoft = Color(0x8007080C)
    val track = Color(0x38FFFFFF)

    /** Glass fills and edges (see Modifier.glass). */
    val glassThin = Color(0x0FFFFFFF)
    val glassRegular = Color(0x1AFFFFFF)
    val glassThick = Color(0x991C1C22)
    val rim = Color(0x2EFFFFFF)
    val rimLight = Color(0x4DFFFFFF)
}

object S {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val safeX = 48.dp
    val safeY = 27.dp
    val radiusSm = 5.dp
    val radiusMd = 8.dp
    val radiusLg = 16.dp
    val radiusPill = 50
}

/** Motion: springs for focus (they settle naturally when you hold a direction), short fades elsewhere. */
object M {
    fun <T> focus(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = 520f)
    fun <T> quick(): FiniteAnimationSpec<T> = tween(140)
    fun <T> medium(): FiniteAnimationSpec<T> = tween(320)
    const val backdropMs = 450
    const val focusScale = 1.06f
    const val buttonScale = 1.05f
    val focusRim: Dp = 2.dp
    val spring = Spring.StiffnessMediumLow
}

/** Inter for Latin; Arabic falls back to the system's Arabic face. */
object T {
    val inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semi_bold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
        Font(R.font.inter_extra_bold, FontWeight.ExtraBold),
        Font(R.font.inter_extra_bold, FontWeight.Black),
    )
    private val f = inter
    val displayXl = TextStyle(fontFamily = f, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, letterSpacing = (-1.2).sp)
    val display = TextStyle(fontFamily = f, fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, color = C.ink, letterSpacing = (-0.6).sp)
    val titleLg = TextStyle(fontFamily = f, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = C.ink, letterSpacing = (-0.3).sp)
    val title = TextStyle(fontFamily = f, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = C.ink, letterSpacing = (-0.2).sp)
    val bodyLg = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, color = C.ink)
    val body = TextStyle(fontFamily = f, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = C.ink)
    val label = TextStyle(fontFamily = f, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
    val caption = TextStyle(fontFamily = f, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, color = C.ink)
    val section = TextStyle(fontFamily = f, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = C.inkFaint, letterSpacing = 1.4.sp)
}
