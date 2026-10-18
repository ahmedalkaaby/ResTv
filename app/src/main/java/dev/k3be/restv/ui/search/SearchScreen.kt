package dev.k3be.restv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.ui.components.FocusRow
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.live.ChannelRow
import dev.k3be.restv.ui.live.ChannelRowContent
import dev.k3be.restv.ui.live.Sidebar
import dev.k3be.restv.ui.live.SidebarItem
import dev.k3be.restv.ui.live.groupVariants
import dev.k3be.restv.ui.live.rememberLiveViewModel
import dev.k3be.restv.ui.onboarding.TvTextField
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import kotlinx.coroutines.delay

/** Live channel search across every category. Movies, series and programmes join when they exist. */
@Composable
fun SearchScreen(
    session: Session,
    onPlay: (List<ChannelRow>, Int) -> Unit,
    onNavigate: (SidebarItem) -> Unit,
) {
    val app = LocalContext.current.applicationContext as RestvApp
    val live = rememberLiveViewModel(session)
    val favs by live.favoriteIds.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChannelRow>>(emptyList()) }
    var categoryNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val field = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        categoryNames = app.db.live().categories(session.login.id).associate { c ->
            c.categoryId to listOf(c.name, c.groupLabel).filter { it.isNotBlank() && it != "Other" }.joinToString(" · ")
        }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(250) // wait for typing to pause
        results = groupVariants(app.db.live().search(session.login.id, q))
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(active = SidebarItem.Search, onSelect = { if (it != SidebarItem.Search) onNavigate(it) })
        Column(
            Modifier.fillMaxHeight().padding(start = 24.dp, top = S.safeY, end = S.safeX),
            verticalArrangement = Arrangement.spacedBy(S.s3),
        ) {
            Txt("Search", T.titleLg)
            Column(Modifier.width(560.dp)) {
                TvTextField("Channel name", query, { query = it }, Modifier.focusRequester(field), keyboardType = KeyboardType.Text)
            }
            Txt(
                when {
                    query.trim().length < 2 -> "Type at least 2 letters. The TV keyboard's mic works for voice search."
                    results.isEmpty() -> "No channels match \"${query.trim()}\"."
                    else -> "${results.size} channels · best quality first"
                },
                T.caption, color = C.inkFaint,
            )
            LazyColumn(Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results.size, key = { results[it].streamId }) { index ->
                    val row = results[index]
                    FocusRow(
                        height = 60.dp,
                        onClick = { onPlay(results, index) },
                        onLongClick = { live.toggleFavorite(row.main) },
                    ) { _ ->
                        ChannelRowContent(row, row.streamId in favs, categoryNames[row.main.categoryId])
                    }
                }
            }
        }
    }
}
