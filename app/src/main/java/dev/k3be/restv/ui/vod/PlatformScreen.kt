package dev.k3be.restv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.k3be.restv.Session
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * One platform's own page: tinted in its colours, its name as the header, then rows of only its
 * titles: newest and best-rated movies and series.
 */
@Composable
fun PlatformScreen(session: Session, platform: String, onOpen: (VodCard) -> Unit) {
    val movies = rememberVodViewModel(session, "movie")
    val series = rememberVodViewModel(session, "series")
    val wishM by movies.wishIds.collectAsStateWithLifecycle()
    val wishS by series.wishIds.collectAsStateWithLifecycle()
    var rails by remember { mutableStateOf<List<Rail>?>(null) }
    var counts by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<VodCard?>(null) }
    LaunchedEffect(platform) {
        coroutineScope {
            val m = async { movies.platformCards(platform) }
            val s = async { series.platformCards(platform) }
            val mr = async { movies.platformCards(platform, byRating = true) }
            val sr = async { series.platformCards(platform, byRating = true) }
            val (mv, sv) = m.await() to s.await()
            counts = listOfNotNull(
                mv.size.takeIf { it > 0 }?.let { "$it movies" },
                sv.size.takeIf { it > 0 }?.let { "$it series" },
            ).joinToString("  ·  ")
            rails = listOfNotNull(
                Rail("p-new-s", "New series", sv.take(40)).takeIf { sv.isNotEmpty() },
                Rail("p-new-m", "New movies", mv.take(40)).takeIf { mv.isNotEmpty() },
                Rail("p-top-s", "Best rated series", sr.await().take(40)).takeIf { it.cards!!.isNotEmpty() },
                Rail("p-top-m", "Best rated movies", mr.await().take(40)).takeIf { it.cards!!.isNotEmpty() },
            )
        }
    }
    val colors = platformColors(platform)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to colors.first(), 0.55f to colors.last(), 1f to C.bg000))) {
        PlatformBackdrop(focused)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = S.safeX, top = S.safeY + 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(platform, T.displayXl.copy(fontSize = 46.sp, fontWeight = FontWeight.Black), color = C.ink)
                Txt(counts.ifEmpty { "Loading…" }, T.label, color = C.inkMuted)
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val hero = focused?.let { Hero(it, null, it.plot, listOfNotNull(it.year?.toString(), it.rating?.let { r -> "★ %.1f".format(r) }, it.genre).joinToString("  ·  ")) }
                HeroInfo(hero, Modifier.align(Alignment.BottomStart))
            }
            val list = rails
            if (list != null) {
                var restored by remember { mutableStateOf(false) }
                BrowseRows(count = list.size, key = { list[it].id }, state = rememberLazyListState()) { i ->
                    val rail = list[i]
                    RailRow(
                        rail = rail,
                        wishIds = wishM + wishS,
                        restoreCardId = if (!restored && i == 0) rail.cards?.firstOrNull()?.id else null,
                        onFocus = { c -> restored = true; focused = c },
                        onClick = onOpen,
                        onLongClick = { c -> (if (c.kind == "movie") movies else series).toggleWish(c) },
                        onSeeAll = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformBackdrop(card: VodCard?) {
    val art = rememberArt(card)
    var shown by remember { mutableStateOf<String?>(null) }
    val target = art?.backdrop ?: card?.backdrop
    LaunchedEffect(target) { delay(150); shown = target }
    if (shown != null) HeroBackdrop(shown)
}
