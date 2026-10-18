package dev.k3be.restv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.k3be.restv.ui.live.LiveScreen
import dev.k3be.restv.ui.onboarding.OnboardingScreen
import dev.k3be.restv.ui.player.PlayerScreen
import dev.k3be.restv.ui.settings.SettingsScreen
import dev.k3be.restv.ui.theme.C

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val route by vm.route.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()

    BackHandler(enabled = route is Route.Player || route is Route.Settings) { vm.back() }

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
                )
            }
            Route.Settings -> session?.let { s ->
                SettingsScreen(session = s, sync = sync, onSync = vm::syncNow, onOpenLive = { vm.go(Route.Live) })
            }
            is Route.Player -> session?.let { s ->
                PlayerScreen(session = s, channels = r.channels, startIndex = r.index, onExit = { vm.back() })
            }
        }
    }
}
