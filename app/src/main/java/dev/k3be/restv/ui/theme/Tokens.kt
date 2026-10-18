package dev.k3be.restv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tokens from the IPTV App design system. 1 dp here = 1 px in the design files (960 × 540 dp canvas). */
object C {
    val bg000 = Color(0xFF0B0B0E)
    val bg100 = Color(0xFF131318)
    val bg200 = Color(0xFF1C1C23)
    val bg300 = Color(0xFF2A2A33)
    val line = Color(0xFF3A3A45)
    val ink = Color(0xFFF4F2EE)
    val inkMuted = Color(0xFFB3B0BA)
    val inkFaint = Color(0xFF7E7B87)
    val accent = Color(0xFFFFB224)
    val accentInk = Color(0xFF1D1404)
    val accentSoft = Color(0x29FFB224)
    val focus = Color(0xFFFFFFFF)
    val focusInk = Color(0xFF0B0B0E)
    val live = Color(0xFFD93036)
    val liveText = Color(0xFFFF6B6F)
    val uhd = Color(0xFF4FB3FF)
    val success = Color(0xFF3ECF8E)
    val warning = accent
    val danger = Color(0xFFFF6B6F)
    val scrimStrong = Color(0xE00B0B0E)
    val scrimSoft = Color(0x730B0B0E)
    val track = Color(0x38F4F2EE)
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
    val radiusSm = 6.dp
    val radiusMd = 10.dp
    val radiusLg = 16.dp
}

/** Manrope is planned; Roboto (system) until the font is bundled. */
object T {
    private val f = FontFamily.Default
    val displayXl = TextStyle(fontFamily = f, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
    val display = TextStyle(fontFamily = f, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = C.ink)
    val titleLg = TextStyle(fontFamily = f, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = C.ink)
    val title = TextStyle(fontFamily = f, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = C.ink)
    val bodyLg = TextStyle(fontFamily = f, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = C.ink)
    val body = TextStyle(fontFamily = f, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = C.ink)
    val label = TextStyle(fontFamily = f, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = C.ink)
    val caption = TextStyle(fontFamily = f, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
    val section = TextStyle(fontFamily = f, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = C.inkFaint, letterSpacing = 0.9.sp)
}
