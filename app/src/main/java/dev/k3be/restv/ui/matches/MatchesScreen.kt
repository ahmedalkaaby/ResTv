package dev.k3be.restv.ui.matches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.data.Match
import dev.k3be.restv.data.MatchesRepo
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.Dp
import dev.k3be.restv.ui.components.Badge
import dev.k3be.restv.ui.components.BadgeKind
import dev.k3be.restv.ui.components.FocusRow
import dev.k3be.restv.ui.components.TvButton
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.live.ChannelGrid
import dev.k3be.restv.ui.live.ChannelRow
import dev.k3be.restv.ui.live.NavScaffold
import dev.k3be.restv.ui.live.SidebarItem
import dev.k3be.restv.ui.live.groupVariants
import dev.k3be.restv.ui.live.rememberLiveViewModel
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

private sealed interface Load {
    data object Loading : Load
    data class Ready(val matches: List<Match>, val note: String) : Load
    data class Error(val message: String) : Load
}

private sealed interface Entry {
    data class Header(val title: String) : Entry
    data class Item(val match: Match) : Entry
}

/** Today's football, your leagues first, with the best channels in your subscription for each match, 4K first. */
@Composable
fun MatchesScreen(
    session: Session,
    onPlay: (List<ChannelRow>, Int) -> Unit,
    onNavigate: (SidebarItem) -> Unit,
) {
    val app = LocalContext.current.applicationContext as RestvApp
    val live = rememberLiveViewModel(session)
    val favs by live.favoriteIds.collectAsStateWithLifecycle()
    var dayOffset by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var selected by remember { mutableStateOf<Match?>(null) }
    var rows by remember { mutableStateOf<List<ChannelRow>>(emptyList()) }
    val covered = remember { mutableStateMapOf<String, MatchesRepo.Coverage>() }
    var looking by remember { mutableStateOf(false) }
    var gridTarget by remember { mutableStateOf<Long?>(null) }
    val firstMatch = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    LaunchedEffect(dayOffset, reload) {
        val date = LocalDate.now().plusDays(dayOffset.toLong()).toString()
        fun ready(r: dev.k3be.restv.data.MatchesRepo.Result, note: String): Load {
            // Only your leagues: Premier League, LaLiga & Real Madrid, Champions League, Bundesliga, Serie A, Ligue 1, Saudi, Iraqi, Süper Lig.
            val sorted = r.matches.filter { (it.broadcasters.isNotEmpty() || it.url != null) && app.matches.priority(it) < 99 }.sortedWith(
                compareBy<Match>({ app.matches.priority(it) }, { it.competition.orEmpty() }, { it.kickoff ?: Long.MAX_VALUE })
            )
            return Load.Ready(sorted, note)
        }
        // Show the saved schedule at once; a fresh one replaces it when it arrives.
        val saved = if (reload == 0) app.matches.cached(date) else null
        load = saved?.let { ready(it, "Saved at ${timeFmt.format(Date(it.fetchedAt))} · checking for updates…") } ?: Load.Loading
        load = try {
            val r = app.matches.fetch(date, force = reload > 0)
            ready(r, "From ${r.source} · updated ${timeFmt.format(Date(r.fetchedAt))}")
        } catch (e: Exception) {
            saved?.let { ready(it, "Saved at ${timeFmt.format(Date(it.fetchedAt))} · couldn't refresh: ${e.message}") }
                ?: Load.Error(e.message ?: "Couldn't load matches")
        }
    }
    LaunchedEffect(load) {
        val l = load as? Load.Ready ?: return@LaunchedEffect
        if (selected == null) runCatching { firstMatch.requestFocus() }
        // Look up your leagues' matches in the background so the list shows real channel counts and crests.
        val gate = Semaphore(2)
        coroutineScope {
            // Only your leagues' matches that are live or start in the next 12 hours: fewer pages to load.
            val now = System.currentTimeMillis()
            l.matches.filter { app.matches.priority(it) < 99 && (it.kickoff == null || it.kickoff in (now - 150 * 60_000)..(now + 12 * 3_600_000)) }
                .take(16).forEach { m ->
                if (m.id in covered) return@forEach
                launch { gate.withPermit { runCatching { covered[m.id] = app.matches.coverage(session.login.id, m) } } }
            }
        }
    }
    LaunchedEffect(selected) {
        val m = selected ?: return@LaunchedEffect
        val ready = covered[m.id]
        if (ready == null) {
            rows = emptyList()
            looking = true
            delay(350) // scrolling past matches shouldn't load every page
        }
        val c = ready ?: app.matches.coverage(session.login.id, m).also { covered[m.id] = it }
        rows = groupVariants(c.channels).sortedWith(
            compareByDescending<ChannelRow> { it.main.quality }.thenBy { it.main.name.lowercase() }
        )
        looking = false
    }

    NavScaffold(active = SidebarItem.Matches, onSelect = onNavigate) {
        Column(Modifier.width(420.dp).fillMaxHeight().padding(top = S.s3)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                TvButton("Today", primary = dayOffset == 0) { dayOffset = 0 }
                TvButton("Tomorrow", primary = dayOffset == 1) { dayOffset = 1 }
                TvButton("↻") { reload++ }
            }
            when (val l = load) {
                Load.Loading -> Txt("Loading…", T.body, color = C.inkMuted, modifier = Modifier.padding(top = S.s4))
                is Load.Error -> Txt(l.message, T.body, color = C.danger, modifier = Modifier.padding(top = S.s4), maxLines = 4)
                is Load.Ready -> {
                    Txt(l.note, T.caption, color = C.inkFaint, modifier = Modifier.padding(top = S.s2))
                    if (l.matches.isEmpty()) Txt("None of your leagues play on this day.", T.body, color = C.inkMuted, modifier = Modifier.padding(top = S.s4))
                    val entries = remember(l) {
                        buildList {
                            var last: String? = null
                            l.matches.forEach { m ->
                                val title = m.competition ?: "Other"
                                if (title != last) { add(Entry.Header(title)); last = title }
                                add(Entry.Item(m))
                            }
                        }
                    }
                    val firstId = l.matches.firstOrNull()?.id
                    LazyColumn(Modifier.padding(top = S.s2), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(entries.size, key = { i ->
                            when (val e = entries[i]) { is Entry.Header -> "h$i"; is Entry.Item -> e.match.id }
                        }) { i ->
                            when (val e = entries[i]) {
                                is Entry.Header -> Txt(e.title, T.caption, color = C.accent, modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else S.s3))
                                is Entry.Item -> {
                                    val m = e.match
                                    val c = covered[m.id]
                                    MatchRow(
                                        m = m,
                                        homeLogo = c?.homeLogo ?: app.matches.logoFor(m, home = true),
                                        awayLogo = c?.awayLogo ?: app.matches.logoFor(m, home = false),
                                        coverage = c,
                                        selected = selected?.id == m.id,
                                        modifier = (if (m.id == firstId) Modifier.focusRequester(firstMatch) else Modifier)
                                            // Right goes to this match's channels, never up to the top bar.
                                            .onPreviewKeyEvent { ev ->
                                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                                                    rows.firstOrNull()?.let { gridTarget = it.streamId }; true
                                                } else false
                                            },
                                    ) { selected = m }
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = S.s4, top = S.s3, end = S.safeX)) {
            val m = selected
            if (m == null) {
                Txt("Pick a match to see your channels for it.", T.body, color = C.inkMuted, modifier = Modifier.padding(top = 12.dp))
                return@Column
            }
            val c = covered[m.id]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                Crest(c?.homeLogo, 36.dp)
                Txt("${m.home} v ${m.away}", T.title)
                Crest(c?.awayLogo, 36.dp)
            }
            Txt(
                listOfNotNull(m.competition, m.kickoff?.let { timeFmt.format(Date(it)) }).joinToString(" · ") +
                    if (c != null) " · ${rows.size} of your channels · 4K first" else "",
                T.caption, color = C.inkMuted,
            )
            if (c != null) {
                val countries = c.countries.values.flatten().groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }.map { it.key }
                if (countries.isNotEmpty()) Txt("From ${countries.joinToString(", ")}", T.caption, color = C.inkFaint, maxLines = 1)
                if (!c.worldwide) Txt("Couldn't load worldwide channels, showing your region only.", T.caption, color = C.inkFaint)
            }
            if (looking) {
                Txt("Finding channels…", T.body, color = C.inkMuted, modifier = Modifier.padding(top = S.s4))
            } else if (rows.isEmpty()) {
                Txt("None of this match's channels were found in your channel list.", T.body, color = C.inkMuted, modifier = Modifier.padding(top = S.s4), maxLines = 3)
            } else {
                ChannelGrid(
                    channels = rows,
                    favorites = favs,
                    nowTitles = emptyMap(),
                    state = gridState,
                    focusTarget = gridTarget,
                    onFocusTargetConsumed = { gridTarget = null },
                    onFocus = {},
                    onPlay = { i -> onPlay(rows, i) },
                    onLongPress = { i -> live.toggleFavorite(rows[i].main) },
                    onLeftEdge = { runCatching { firstMatch.requestFocus() } },
                    columns = 2,
                    modifier = Modifier.fillMaxSize().padding(top = S.s2),
                )
            }
        }
    }
}

@Composable
private fun Crest(url: String?, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun MatchRow(
    m: Match,
    homeLogo: String?,
    awayLogo: String?,
    coverage: MatchesRepo.Coverage?,
    selected: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val isLive = m.kickoff != null && now >= m.kickoff && now - m.kickoff < 2 * 60 * 60_000
    FocusRow(modifier = modifier, selected = selected, height = 64.dp, onFocused = onFocus, onClick = onFocus) { _ ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(S.s3),
        ) {
            Txt(m.kickoff?.let { timeFmt.format(Date(it)) } ?: "--:--", T.label, color = if (isLive) C.liveText else C.inkMuted, modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Crest(homeLogo, 20.dp); Txt(m.home, T.label)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Crest(awayLogo, 20.dp); Txt(m.away, T.label)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isLive) Badge("LIVE", BadgeKind.Live)
                if (coverage != null) {
                    val n = groupVariants(coverage.channels).size
                    if (coverage.channels.any { it.quality == 3 }) Badge("4K", BadgeKind.Uhd)
                    Txt(if (n == 0) "no channels" else "$n ch", T.caption, color = C.inkFaint)
                }
            }
        }
    }
}
