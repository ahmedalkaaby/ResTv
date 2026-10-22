package dev.k3be.restv

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.CompositionLocalProvider
import dev.k3be.restv.ui.home.HomeScreen
import dev.k3be.restv.ui.live.LocalTopBarFocus
import dev.k3be.restv.ui.live.TopBarFocus
import dev.k3be.restv.ui.components.Toast
import dev.k3be.restv.ui.vod.MovieDetailScreen
import dev.k3be.restv.ui.vod.SeriesDetailScreen
import dev.k3be.restv.ui.vod.VodGridScreen
import dev.k3be.restv.ui.vod.VodHomeScreen
import dev.k3be.restv.ui.vod.VodPlayerScreen
import dev.k3be.restv.ui.live.LiveScreen
import dev.k3be.restv.ui.live.SidebarItem
import dev.k3be.restv.ui.search.SearchScreen
import dev.k3be.restv.ui.matches.MatchesScreen
import dev.k3be.restv.ui.onboarding.OnboardingScreen
import dev.k3be.restv.ui.player.PlayerScreen
import dev.k3be.restv.ui.settings.SettingsScreen
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

/** How long a second Back press counts as confirming the first. */
const val BACK_CONFIRM_MS = 2_000L

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val route by vm.route.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    var lastBack by remember { mutableLongStateOf(0L) }
    var exitHint by remember { mutableStateOf(false) }
    LaunchedEffect(exitHint) { if (exitHint) { delay(BACK_CONFIRM_MS); exitHint = false } }

    val topBar = remember { TopBarFocus() }
    // Back: closes details and players first. On a tab, the first press moves focus up to the top bar,
    // the next goes Home, and on Home a second press (with a hint) leaves the app.
    BackHandler(enabled = route !is Route.Onboarding && route !is Route.Player && route !is Route.VodPlayer && route !is Route.Loading) {
        if (vm.back()) return@BackHandler
        when {
            !topBar.hasFocus -> runCatching { topBar.requester.requestFocus() }
            route != Route.Home -> vm.go(Route.Home)
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < BACK_CONFIRM_MS) activity?.finish() else { lastBack = now; exitHint = true }
            }
        }
    }
    val saveable = rememberSaveableStateHolder()
    val openCard: (dev.k3be.restv.ui.vod.VodCard) -> Unit = { c ->
        vm.go(
            when (c.kind) {
                "platform" -> Route.Platform(c.title)
                "movie" -> Route.MovieDetail(c.id)
                else -> Route.SeriesDetail(c.id)
            }
        )
    }
    val navigate: (SidebarItem) -> Unit = { item ->
        vm.go(
            when (item) {
                SidebarItem.Search -> Route.Search
                SidebarItem.Home -> Route.Home
                SidebarItem.Live -> Route.Live
                SidebarItem.Movies -> Route.Movies
                SidebarItem.Series -> Route.Series
                SidebarItem.Matches -> Route.Matches
                SidebarItem.Settings -> Route.Settings
            }
        )
    }
    // Leaving onboarding takes two presses, so a stray Back never closes it.
    BackHandler(enabled = route is Route.Onboarding) {
        val now = System.currentTimeMillis()
        if (now - lastBack < BACK_CONFIRM_MS) activity?.finish()
        else { lastBack = now; exitHint = true }
    }

    var splashDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1_300); splashDone = true }
    CompositionLocalProvider(LocalTopBarFocus provides topBar) {
    Box(Modifier.fillMaxSize().background(C.bg000)) {
        when (val r = route) {
            Route.Home -> session?.let { s ->
                saveable.SaveableStateProvider("home") {
                    HomeScreen(
                        session = s,
                        onOpen = openCard,
                        onPlayVod = { q, i -> vm.go(Route.VodPlayer(q, i)) },
                        onPlayLive = { list, index -> vm.go(Route.Player(list, index)) },
                        onNavigate = navigate,
                    )
                }
            }
            Route.Loading -> dev.k3be.restv.ui.Splash()
            Route.Onboarding -> OnboardingScreen(sync = sync, onConnect = vm::connect)
            Route.Live -> session?.let { s ->
                LiveScreen(
                    session = s,
                    sync = sync,
                    onPlay = { list, index -> vm.go(Route.Player(list, index)) },
                    onNavigate = navigate,
                )
            }
            Route.Search -> session?.let { s ->
                SearchScreen(
                    session = s,
                    onPlay = { list, index -> vm.go(Route.Player(list, index)) },
                    onOpenVod = openCard,
                    onNavigate = navigate,
                )
            }
            Route.Matches -> session?.let { s ->
                MatchesScreen(
                    session = s,
                    onPlay = { list, index -> vm.go(Route.Player(list, index)) },
                    onNavigate = navigate,
                )
            }
            Route.Settings -> session?.let { s ->
                SettingsScreen(session = s, sync = sync, onSync = vm::syncNow, onNavigate = navigate)
            }
            Route.Movies, Route.Series -> session?.let { s ->
                val kind = if (r == Route.Movies) "movie" else "series"
                saveable.SaveableStateProvider(kind) {
                    VodHomeScreen(
                        session = s,
                        kind = kind,
                        sync = sync,
                        onDownload = { vm.syncVodNow(kind) },
                        onOpen = openCard,
                        onSeeAll = { rail -> vm.go(Route.VodGrid(kind, rail.id, rail.title)) },
                        onNavigate = navigate,
                    )
                }
            }
            is Route.VodGrid -> session?.let { s ->
                VodGridScreen(s, r.kind, r.railId, r.title, openCard)
            }
            is Route.Platform -> session?.let { s ->
                key(r.name) { dev.k3be.restv.ui.vod.PlatformScreen(s, r.name, openCard) }
            }
            is Route.MovieDetail -> session?.let { s ->
                key(r.id) { MovieDetailScreen(s, r.id) { q, i, fresh -> vm.go(Route.VodPlayer(q, i, fresh)) } }
            }
            is Route.SeriesDetail -> session?.let { s ->
                key(r.id) { SeriesDetailScreen(s, r.id) { q, i, fresh -> vm.go(Route.VodPlayer(q, i, fresh)) } }
            }
            is Route.VodPlayer -> session?.let { s ->
                key(r) { VodPlayerScreen(s, r.queue, r.index, r.fromStart, onExit = { vm.back() }) }
            }
            is Route.Player -> session?.let { s ->
                PlayerScreen(session = s, rows = r.channels, startIndex = r.index, onExit = { vm.back() })
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = !splashDone,
            enter = androidx.compose.animation.EnterTransition.None,
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(350)),
        ) { dev.k3be.restv.ui.Splash() }
        CrashReport()
        if (exitHint) {
            Toast("Press Back again to exit", Modifier.align(Alignment.BottomCenter).padding(bottom = S.safeY + S.s4))
        }
    }
    }
}

/** After a crash, shows what went wrong so it can be photographed and fixed. */
@Composable
private fun CrashReport() {
    val app = LocalContext.current.applicationContext as RestvApp
    var text by remember { mutableStateOf(runCatching { app.crashFile.takeIf { it.exists() }?.readText() }.getOrNull()) }
    val t = text ?: return
    val ok = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { ok.requestFocus() } }
    fun dismiss() { app.crashFile.delete(); text = null }
    BackHandler { dismiss() }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(C.bg000.copy(alpha = 0.97f)).padding(horizontal = S.safeX, vertical = S.safeY),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(S.s3),
    ) {
        dev.k3be.restv.ui.components.Txt("Restv closed unexpectedly last time", dev.k3be.restv.ui.theme.T.titleLg)
        dev.k3be.restv.ui.components.Txt("Take a photo of this screen and send it over, then press OK.", dev.k3be.restv.ui.theme.T.body, color = C.inkMuted)
        dev.k3be.restv.ui.components.TvButton("OK", primary = true, modifier = Modifier.focusRequester(ok)) { dismiss() }
        dev.k3be.restv.ui.components.Txt(
            t.lines().filter { it.isNotBlank() }.take(28).joinToString("\n"),
            dev.k3be.restv.ui.theme.T.caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = C.inkMuted, maxLines = 28,
        )
    }
}
