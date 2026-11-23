package dev.k3be.restv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.k3be.restv.RestvApp
import dev.k3be.restv.data.Person
import dev.k3be.restv.data.TitleDetails
import dev.k3be.restv.data.VodNaming
import dev.k3be.restv.ui.components.Glass
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.glass
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.M
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T

/** TMDB details for a title (null until loaded, or without a TMDB key). */
@Composable
fun rememberTitleDetails(kind: String, title: String?, year: Int?, tmdbId: String?): TitleDetails? {
    val app = LocalContext.current.applicationContext as RestvApp
    val state by produceState<TitleDetails?>(null, kind, title, year, tmdbId) {
        if (title.isNullOrBlank()) return@produceState
        value = app.art.details(kind, title, year, tmdbId)
    }
    return state
}

/** TMDB's recommendations that exist in this subscription, one copy each, best version first. */
@Composable
fun rememberSimilar(loginId: Long, kind: String, selfKey: String?, details: TitleDetails?): List<VodCard> {
    val app = LocalContext.current.applicationContext as RestvApp
    val state by produceState(emptyList<VodCard>(), details, selfKey) {
        val wanted = details?.similar.orEmpty()
        if (wanted.isEmpty()) return@produceState
        val keys = wanted.map { VodNaming.key(it.first) }.distinct().filter { it != selfKey }
        value = if (kind == "movie") {
            val found = app.db.vod().moviesByKeys(loginId, keys).groupBy { it.titleKey }
            keys.mapNotNull { k ->
                val list = found[k] ?: return@mapNotNull null
                val m = VodNaming.pickVersion(list, { VodNaming.source(it.rawName) }, { it.quality })
                VodCard("movie", m.streamId, m.name, m.year, m.poster, m.rating, m.quality, source = VodNaming.source(m.rawName))
            }
        } else {
            val found = app.db.vod().seriesByKeys(loginId, keys).groupBy { it.titleKey }
            keys.mapNotNull { k ->
                val s = found[k]?.firstOrNull() ?: return@mapNotNull null
                VodCard("series", s.seriesId, s.name, s.year, s.poster, s.rating, backdrop = s.backdrop, source = VodNaming.source(s.rawName))
            }
        }.take(20)
    }
    return state
}

/**
 * Below a details page's top: cast & crew, the facts, and more like this.
 * [providerCast] ("A, B, C") is used when TMDB isn't available.
 */
@Composable
fun DetailExtras(
    details: TitleDetails?,
    providerCast: String?,
    facts: List<Pair<String, String>>,
    similar: List<VodCard>,
    onOpen: (VodCard) -> Unit,
) {
    val people = details?.cast?.takeIf { it.isNotEmpty() }
        ?: providerCast?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(15)?.map { Person(it, null, null) }.orEmpty()
    Column(Modifier.fillMaxWidth().padding(bottom = S.safeY + 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (people.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Cast & crew", T.title, modifier = Modifier.padding(start = S.safeX))
                KeepInView {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = S.safeX, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        val crew = details?.directors.orEmpty().map { Person(it, "Director", null) }
                        val all = people + crew
                        items(all.size) { i -> PersonCard(all[i]) }
                    }
                }
            }
        }
        TrailersRow(details?.videos.orEmpty())
        if (facts.isNotEmpty()) FactsPanel(details?.tagline, facts)
        if (similar.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("More like this", T.title, modifier = Modifier.padding(start = S.safeX))
                KeepInView {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = S.safeX, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(similar.size, key = { "${similar[it].kind}${similar[it].id}" }) { i ->
                            val c = similar[i]
                            PosterCard(card = c, inList = false, showTitle = true, onFocus = {}, onClick = { onOpen(c) }, onLongClick = { onOpen(c) })
                        }
                    }
                }
            }
        }
    }
}

/** A round photo (or initials), the name and the part they play. */
@Composable
private fun PersonCard(p: Person) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.08f else 1f, M.focus(), label = "person")
    Column(
        Modifier.width(92.dp).remoteClickable(source, {}),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(80.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(C.bg300, C.bg100)))
                .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val initials = p.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
            Txt(initials, T.title, color = C.inkMuted)
            if (p.photo != null) AsyncImage(p.photo, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Txt(p.name, T.caption.copy(textAlign = TextAlign.Center), color = if (focused) C.ink else C.ink.copy(alpha = 0.9f), maxLines = 2)
        p.role?.let { Txt(it, T.caption.copy(textAlign = TextAlign.Center), color = C.inkFaint, maxLines = 1) }
    }
}

/** The facts in a glass panel; focusable so the page can scroll to it. */
@Composable
private fun FactsPanel(tagline: String?, facts: List<Pair<String, String>>) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(S.radiusLg)
    Column(
        Modifier
            .padding(horizontal = S.safeX)
            .fillMaxWidth()
            .glass(shape, 1f, Glass.Regular)
            .border(1.dp, if (focused) Color.White.copy(alpha = 0.7f) else Color.Transparent, shape)
            .remoteClickable(source, {})
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt("About", T.title)
        tagline?.let { Txt("“$it”", T.bodyLg.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = C.inkMuted, maxLines = 2) }
        facts.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Txt(label.uppercase(), T.section)
                        Txt(value, T.label, maxLines = 2)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** The facts for the panel, from TMDB when available, else the provider's info. */
fun detailFacts(
    d: TitleDetails?,
    kind: String,
    providerGenre: String?,
    providerDirector: String?,
    runtimeMs: Long?,
    rating: Float?,
    year: Int?,
): List<Pair<String, String>> = buildList {
    val people = d?.directors?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: providerDirector?.takeIf { it.isNotBlank() }
    people?.let { add((if (kind == "movie") "Director" else "Created by") to it) }
    d?.writers?.takeIf { it.isNotEmpty() }?.let { add("Writers" to it.joinToString(", ")) }
    (d?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: providerGenre?.takeIf { it.isNotBlank() })?.let { add("Genres" to it) }
    val mins = d?.runtimeMin ?: runtimeMs?.takeIf { it > 0 }?.let { (it / 60_000).toInt() }
    mins?.let { add((if (kind == "movie") "Runtime" else "Episode length") to (if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min")) }
    (d?.released?.let(::prettyDate) ?: year?.toString())?.let { add((if (kind == "movie") "Released" else "First aired") to it) }
    d?.certification?.let { add("Rated" to it) }
    val score = d?.score?.let { "%.1f / 10".format(it) + (d.votes?.takeIf { v -> v > 0 }?.let { v -> " · ${"%,d".format(v)} votes" } ?: "") }
        ?: rating?.let { "%.1f / 10".format(it) }
    score?.let { add("Score" to it) }
    d?.language?.let { add("Language" to it) }
    d?.studios?.takeIf { it.isNotEmpty() }?.let { add((if (kind == "movie") "Studio" else "Network") to it.joinToString(", ")) }
    if (kind != "movie") {
        d?.seasons?.let { s -> add("Seasons" to "$s" + (d.episodes?.let { e -> " · $e episodes" } ?: "")) }
        d?.status?.let { add("Status" to it) }
    }
}

/** "2014-11-05" → "5 Nov 2014". */
private fun prettyDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
}.getOrDefault(iso)
