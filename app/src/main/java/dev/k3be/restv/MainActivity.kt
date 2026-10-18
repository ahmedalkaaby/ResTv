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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.k3be.restv.ui.components.Toast
import dev.k3be.restv.ui.live.LiveScreen
import dev.k3be.restv.ui.live.SidebarItem
import dev.k3be.restv.ui.search.SearchScreen
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

    BackHandler(enabled = route is Route.Settings || route is Route.Search) { vm.back() }
    // Leaving the app takes two presses, so a stray Back never closes it.
    BackHandler(enabled = route is Route.Live || route is Route.Onboarding) {
        val now = System.currentTimeMillis()
        if (now - lastBack < BACK_CONFIRM_MS) activity?.finish()
        else { lastBack = now; exitHint = true }
    }

    Box(Modifier.fillMaxSize().background(C.bg000)) {
        when (val r = route) {
            Route.Loading -> {}
            Route.Onboarding -> OnboardingScreen(sync = sync, onConnect = vm::connect)
            Route.Live -> session?.let { s ->
                LiveScreen(
                    session = s,
                    sync = sync,
                    onPlay = { list, index -> vm.go(Route.Player(list, index)) },
                    onOpenSettings = { vm.go(Route.Settings) },
                    onOpenSearch = { vm.go(Route.Search) },
                )
            }
            Route.Search -> session?.let { s ->
                SearchScreen(
                    session = s,
                    onPlay = { list, index -> vm.go(Route.Player(list, index)) },
                    onNavigate = { item -> vm.go(if (item == SidebarItem.Settings) Route.Settings else Route.Live) },
                )
            }
            Route.Settings -> session?.let { s ->
                SettingsScreen(session = s, sync = sync, onSync = vm::syncNow, onNavigate = { item ->
                    vm.go(if (item == SidebarItem.Search) Route.Search else Route.Live)
                })
            }
            is Route.Player -> session?.let { s ->
                PlayerScreen(session = s, rows = r.channels, startIndex = r.index, onExit = { vm.back() })
            }
        }
        if (exitHint) {
            Toast("Press Back again to exit", Modifier.align(Alignment.BottomCenter).padding(bottom = S.safeY + S.s4))
        }
    }
}
