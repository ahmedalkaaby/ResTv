package dev.k3be.restv.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.k3be.restv.data.Broadcasters
import dev.k3be.restv.data.Match
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.focusGlow
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import dev.k3be.restv.ui.vod.RAIL_H
import dev.k3be.restv.ui.vod.RailHeader
import dev.k3be.restv.ui.vod.WIDE_H
import dev.k3be.restv.ui.vod.WIDE_W
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** A match of one of your teams, with crests resolved. */
data class MatchDay(val match: Match, val homeLogo: String?, val awayLogo: String?)

enum class Pattern { Stars, Stripes, Arcs }

/** Each competition's look: drawn colours and a light pattern, no downloaded artwork. */
data class LeagueTheme(val name: String, val top: Color, val bottom: Color, val accent: Color, val pattern: Pattern)

fun leagueTheme(m: Match): LeagueTheme = when (Broadcasters.competition(m.league, m.competition)) {
    "pl" -> LeagueTheme("Premier League", Color(0xFF38003C), Color(0xFF12001A), Color(0xFF00FF85), Pattern.Arcs)
    "laliga" -> LeagueTheme("LaLiga", Color(0xFF4A0D10), Color(0xFF140304), Color(0xFFFF4B44), Pattern.Stripes)
    "ucl" -> LeagueTheme("Champions League", Color(0xFF0B2266), Color(0xFF020A26), Color(0xFFDCE6FF), Pattern.Stars)
    "uel" -> LeagueTheme("Europa League", Color(0xFF3F1D00), Color(0xFF110700), Color(0xFFFF7A1A), Pattern.Arcs)
    "uecl" -> LeagueTheme("Conference League", Color(0xFF04361F), Color(0xFF010D07), Color(0xFF2EE65A), Pattern.Arcs)
    "bundesliga" -> LeagueTheme("Bundesliga", Color(0xFF44000A), Color(0xFF110003), Color(0xFFFF2B3A), Pattern.Stripes)
    "seriea" -> LeagueTheme("Serie A", Color(0xFF062B55), Color(0xFF010B19), Color(0xFF3BBEFF), Pattern.Arcs)
    "ligue1" -> LeagueTheme("Ligue 1", Color(0xFF0B2048), Color(0xFF020817), Color(0xFFDAE025), Pattern.Stripes)
    "saudi" -> LeagueTheme("Saudi Pro League", Color(0xFF053D31), Color(0xFF01100C), Color(0xFF2DD4A3), Pattern.Stripes)
    "iraq" -> LeagueTheme("Iraq Stars League", Color(0xFF0B421F), Color(0xFF021008), Color(0xFF6EE7A0), Pattern.Stripes)
    "turkey" -> LeagueTheme("Süper Lig", Color(0xFF450B12), Color(0xFF12030A), Color(0xFFFF3B45), Pattern.Arcs)
    "intl" -> LeagueTheme("International", Color(0xFF47111B), Color(0xFF110309), Color(0xFFEBC56E), Pattern.Stars)
    else -> LeagueTheme("Football", Color(0xFF23232D), Color(0xFF0B0B0E), C.accent, Pattern.Arcs)
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Off only in screenshot tests, where a running clock never lets the UI settle. */
internal var clockTicks = true

/** Ticks every [stepMs]; only the composables that show a clock recompose. */
@Composable
fun rememberNow(stepMs: Long = 1_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (!clockTicks) return now
    LaunchedEffect(stepMs) {
        while (true) {
            delay(stepMs - System.currentTimeMillis() % stepMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private const val MATCH_LENGTH_MS = 115 * 60_000L

fun isOver(m: Match, now: Long = System.currentTimeMillis()) = m.kickoff != null && now > m.kickoff + MATCH_LENGTH_MS

/** "Kick-off in 02:14:33", "LIVE", "Full time"; [seconds] off for the small cards. */
private fun status(m: Match, now: Long, seconds: Boolean): Pair<String, Boolean> {
    val k = m.kickoff ?: return "Today" to false
    return when {
        now < k -> {
            val s = (k - now) / 1000
            val h = s / 3600; val mi = (s % 3600) / 60; val se = s % 60
            (if (seconds) "Kick-off in %02d:%02d:%02d".format(h, mi, se)
            else if (h > 0) "in ${h}h ${mi}m" else "in ${mi + 1} min") to false
        }
        now < k + MATCH_LENGTH_MS -> "LIVE" to true
        else -> "Full time" to false
    }
}

/** The page behind a focused match card: the competition's colours, a pattern, and both crests large. */
@Composable
fun MatchBackdrop(day: MatchDay) {
    val theme = leagueTheme(day.match)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(theme.top, theme.bottom)))) {
        PatternLayer(theme)
        // Crests on the right, like a match poster.
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = S.safeX + 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Crest(day.homeLogo, day.match.home, 140.dp)
            Txt("v", T.displayXl.copy(fontSize = 40.sp, fontWeight = FontWeight.Black), color = theme.accent.copy(alpha = 0.9f))
            Crest(day.awayLogo, day.match.away, 140.dp)
        }
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 0.8f to C.bg000.copy(alpha = 0.8f), 1f to C.bg000)
        ))
    }
}

@Composable
private fun BoxScope.PatternLayer(theme: LeagueTheme) {
    Canvas(Modifier.matchParentSize()) {
        // A soft light from the top right.
        drawRect(Brush.radialGradient(
            listOf(theme.accent.copy(alpha = 0.26f), Color.Transparent),
            center = Offset(size.width * 0.8f, size.height * 0.1f), radius = size.width * 0.5f,
        ))
        when (theme.pattern) {
            Pattern.Stars -> stars(Color.White)
            Pattern.Stripes -> {
                val step = 34.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(theme.accent.copy(alpha = 0.06f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 10.dp.toPx())
                    x += step
                }
            }
            Pattern.Arcs -> {
                val c = Offset(size.width * 0.82f, size.height * 0.3f)
                for (i in 1..9) drawCircle(theme.accent.copy(alpha = 0.07f), radius = i * 70.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** A scatter of small five-point stars, the same every time. */
private fun DrawScope.stars(color: Color) {
    val rnd = java.util.Random(7)
    repeat(46) {
        val cx = rnd.nextFloat() * size.width
        val cy = rnd.nextFloat() * size.height * 0.8f
        val r = (4 + rnd.nextInt(10)).dp.toPx()
        val path = Path()
        for (k in 0 until 10) {
            val rad = if (k % 2 == 0) r else r * 0.42f
            val a = Math.toRadians(-90.0 + k * 36.0)
            val px = cx + (rad * cos(a)).toFloat(); val py = cy + (rad * sin(a)).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        drawPath(path, color.copy(alpha = 0.05f + rnd.nextFloat() * 0.12f))
    }
}

/** Bottom-left text for a focused match: competition, teams, a live countdown. */
@Composable
fun MatchHero(day: MatchDay, modifier: Modifier) {
    val m = day.match
    val theme = leagueTheme(m)
    val now = rememberNow()
    val (line, live) = status(m, now, seconds = true)
    Column(modifier.padding(start = S.safeX, end = S.safeX, bottom = S.s4).width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt((m.competition ?: theme.name).uppercase(), T.label.copy(letterSpacing = 2.sp), color = theme.accent)
        Txt("${m.home}  v  ${m.away}", T.displayXl, maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            m.kickoff?.let { Txt("Today · ${timeFmt.format(Date(it))}", T.bodyLg, color = C.inkMuted) }
            Txt(line, T.title, color = if (live) C.ink else theme.accent,
                modifier = if (live) Modifier.background(C.live, RoundedCornerShape(S.radiusSm)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier)
        }
        Txt("OK · see the channels for this match", T.body, color = C.inkFaint)
    }
}

/** "Your teams today" row: one themed card per match. */
@Composable
fun MatchDayRail(
    title: String,
    days: List<MatchDay>,
    nextTitle: String?,
    restoreId: String?,
    onFocus: (MatchDay) -> Unit,
    onClick: (MatchDay) -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(RAIL_H), verticalArrangement = Arrangement.spacedBy(S.s2)) {
        RailHeader(title, true, nextTitle, S.safeX)
        dev.k3be.restv.ui.vod.KeepInView { LazyRow(
            contentPadding = PaddingValues(start = S.safeX, end = S.safeX, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(days.size, key = { days[it].match.id }) { i ->
                val d = days[i]
                MatchCard(d, requestFocus = restoreId == d.match.id, onFocus = { onFocus(d) }, onClick = { onClick(d) })
            }
        } }
    }
}

@Composable
private fun MatchCard(day: MatchDay, requestFocus: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fr = remember { FocusRequester() }
    if (focused) LaunchedEffect(Unit) { onFocus() }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(90), label = "match")
    val shape = RoundedCornerShape(S.radiusMd)
    val m = day.match
    val theme = leagueTheme(m)
    val now = rememberNow(30_000)
    val (line, live) = status(m, now, seconds = false)
    Box(
        Modifier
            .size(WIDE_W, WIDE_H)
            .focusGlow(focused, shape)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.shape = shape; clip = true }
            .background(Brush.linearGradient(listOf(theme.top, theme.bottom)))
            .border(1.5.dp, if (focused) Color.White.copy(alpha = 0.92f) else theme.accent.copy(alpha = 0.25f), shape)
            .focusRequester(fr)
            .remoteClickable(source, onClick),
    ) {
        Txt((m.competition ?: theme.name).uppercase(), T.caption.copy(fontSize = 10.sp, letterSpacing = 1.sp), color = theme.accent,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 8.dp, end = 10.dp))
        Row(
            Modifier.align(Alignment.Center).padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Crest(day.homeLogo, m.home, 50.dp)
            Txt(m.kickoff?.let { timeFmt.format(Date(it)) } ?: "v", T.title, color = C.ink)
            Crest(day.awayLogo, m.away, 50.dp)
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${short(m.home)} v ${short(m.away)}", T.caption, color = C.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Txt(line, T.caption, color = if (live) C.ink else theme.accent,
                modifier = if (live) Modifier.background(C.live, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp) else Modifier)
        }
    }
}

private fun short(team: String) = if (team.length <= 14) team else team.split(' ').firstOrNull { it.length >= 4 } ?: team.take(12)

/** The crest, or the team's initials in a circle when there's none. */
@Composable
private fun Crest(url: String?, team: String, size: Dp) {
    var failed by remember(url) { mutableStateOf(url == null) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (!failed) {
            AsyncImage(url, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), onError = { failed = true })
        } else {
            val initials = fifa[dev.k3be.restv.data.Teams.key(team)]
                ?: team.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                Txt(initials, T.title.copy(fontSize = (size.value * 0.32f).sp), color = C.ink)
            }
        }
    }
}

/** Three-letter codes for national teams without a crest. */
private val fifa = mapOf(
    "iraq" to "IRQ", "saudi arabia" to "KSA", "jordan" to "JOR", "morocco" to "MAR", "egypt" to "EGY", "algeria" to "ALG",
    "qatar" to "QAT", "united arab emirates" to "UAE", "oman" to "OMA", "kuwait" to "KUW", "bahrain" to "BHR", "syria" to "SYR",
    "lebanon" to "LBN", "palestine" to "PLE", "iran" to "IRN", "japan" to "JPN", "south korea" to "KOR", "australia" to "AUS",
    "uzbekistan" to "UZB", "china" to "CHN", "indonesia" to "IDN", "tunisia" to "TUN", "united states" to "USA", "mexico" to "MEX",
    "spain" to "ESP", "england" to "ENG", "france" to "FRA", "germany" to "GER", "portugal" to "POR", "brazil" to "BRA",
    "argentina" to "ARG", "italy" to "ITA", "netherlands" to "NED", "belgium" to "BEL", "croatia" to "CRO", "turkey" to "TUR",
)
