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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.data.Match
import dev.k3be.restv.ui.components.Badge
import dev.k3be.restv.ui.components.BadgeKind
import dev.k3be.restv.ui.components.FocusRow
import dev.k3be.restv.ui.components.TvButton
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.live.ChannelGrid
import dev.k3be.restv.ui.live.ChannelRow
import dev.k3be.restv.ui.live.Sidebar
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
    data class Ready(val matches: List<Match>) : Load
    data class Error(val message: String) : Load
}

/** Today's football with the best channels in your subscription for each match, 4K first. */
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
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var selected by remember { mutableStateOf<Match?>(null) }
    var rows by remember { mutableStateOf<List<ChannelRow>>(emptyList()) }
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }
    val firstMatch = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    LaunchedEffect(dayOffset) {
        load = Load.Loading
        load = try {
            val date = LocalDate.now().plusDays(dayOffset.toLong()).toString()
            Load.Ready(app.matches.fetch(date).filter { it.broadcasters.isNotEmpty() })
        } catch (e: Exception) {
            Load.Error(e.message ?: "Couldn't load matches")
        }
    }
    LaunchedEffect(load) { if (load is Load.Ready) runCatching { firstMatch.requestFocus() } }
    LaunchedEffect(selected) {
        val m = selected ?: return@LaunchedEffect
        val (channels, notFound) = app.matches.channelsFor(session.login.id, m.broadcasters)
        rows = groupVariants(channels).sortedWith(
            compareByDescending<ChannelRow> { it.main.quality }.thenBy { it.main.name.lowercase() }
        )
        missing = notFound
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(active = SidebarItem.Matches, onSelect = { if (it != SidebarItem.Matches) onNavigate(it) })
        Column(Modifier.width(360.dp).fillMaxHeight().padding(start = 24.dp, top = S.safeY)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                Txt("Matches", T.titleLg, modifier = Modifier.padding(end = 8.dp))
                TvButton("Today", primary = dayOffset == 0) { dayOffset = 0 }
                TvButton("Tomorrow", primary = dayOffset == 1) { dayOffset = 1 }
            }
            when (val l = load) {
                Load.Loading -> Txt("Loading…", T.body, color = C.inkMuted, modifier = Modifier.padding(top = S.s4))
                is Load.Error -> Txt(l.message, T.body, color = C.danger, modifier = Modifier.padding(top = S.s4), maxLines = 4)
                is Load.Ready -> {
                    if (l.matches.isEmpty()) Txt("No televised matches found for this day.", T.body, color = C.inkMuted, modifier = Modifier.padding(top = S.s4))
                    LazyColumn(Modifier.padding(top = S.s3), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(l.matches.size, key = { l.matches[it].id }) { i ->
                            val m = l.matches[i]
                            MatchRow(m, selected?.id == m.id, if (i == 0) Modifier.focusRequester(firstMatch) else Modifier) { selected = m }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = S.s4, top = S.safeY, end = S.safeX)) {
            val m = selected
            if (m == null) {
                Txt("Pick a match to see your channels for it.", T.body, color = C.inkMuted, modifier = Modifier.padding(top = 44.dp))
                return@Column
            }
            Txt("${m.home} v ${m.away}", T.title)
            Txt(
                listOfNotNull(m.competition, m.kickoff?.let { timeFmt.format(Date(it)) }).joinToString(" · ") +
                    " · ${rows.size} of your channels · 4K first",
                T.caption, color = C.inkMuted,
            )
            if (missing.isNotEmpty()) {
                Txt("Not in your subscription: ${missing.joinToString(", ")}", T.caption, color = C.inkFaint, maxLines = 2)
            }
            if (rows.isEmpty()) {
                Txt("None of this match's channels were found in your channel list.", T.body, color = C.inkMuted, modifier = Modifier.padding(top = S.s4), maxLines = 3)
            } else {
                ChannelGrid(
                    channels = rows,
                    favorites = favs,
                    nowTitles = emptyMap(),
                    state = gridState,
                    focusTarget = null,
                    onFocusTargetConsumed = {},
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
private fun MatchRow(m: Match, selected: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    val now = System.currentTimeMillis()
    val isLive = m.kickoff != null && now >= m.kickoff && now - m.kickoff < 2 * 60 * 60_000
    FocusRow(modifier = modifier, selected = selected, height = 56.dp, onFocused = onFocus, onClick = onFocus) { _ ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(S.s3),
        ) {
            Txt(m.kickoff?.let { timeFmt.format(Date(it)) } ?: "--:--", T.label, color = if (isLive) C.liveText else C.inkMuted, modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                Txt("${m.home} v ${m.away}", T.label)
                Txt(m.competition ?: "", T.caption, color = C.inkFaint)
            }
            if (isLive) Badge("LIVE", BadgeKind.Live) else Txt("${m.broadcasters.size} ch", T.caption, color = C.inkFaint)
        }
    }
}
