package dev.k3be.restv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.SyncState
import dev.k3be.restv.data.Prefs
import dev.k3be.restv.ui.components.Icons
import dev.k3be.restv.ui.components.SectionLabel
import dev.k3be.restv.ui.components.TvButton
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.live.Sidebar
import dev.k3be.restv.ui.live.SidebarItem
import dev.k3be.restv.ui.live.rememberLiveViewModel
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import dev.k3be.restv.update.Updater
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val fraction: Float) : UpdateState
    data class Error(val message: String) : UpdateState
}

@Composable
fun SettingsScreen(session: Session, sync: SyncState, onSync: () -> Unit, onOpenLive: () -> Unit) {
    val app = LocalContext.current.applicationContext as RestvApp
    val live = rememberLiveViewModel(session)
    val hidden by live.hiddenCount.collectAsStateWithLifecycle()
    val agent by app.prefs.agent.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun checkUpdate() {
        scope.launch {
            update = UpdateState.Checking
            update = try {
                app.updater.check()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: "Couldn't reach GitHub")
            }
        }
    }

    fun installUpdate(release: Updater.Release) {
        if (app.updater.needsInstallPermission()) {
            app.updater.openInstallPermissionSettings()
            return
        }
        scope.launch {
            try {
                val file = app.updater.download(release) { update = UpdateState.Downloading(it) }
                update = UpdateState.Available(release)
                app.updater.install(file)
            } catch (e: Exception) {
                update = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(active = SidebarItem.Settings, onSelect = { if (it == SidebarItem.Live) onOpenLive() })
        Column(
            Modifier.fillMaxHeight().padding(start = 24.dp, top = S.safeY, end = S.safeX),
            verticalArrangement = Arrangement.spacedBy(S.s3),
        ) {
            Txt("Settings", T.titleLg)

            Card {
                SectionLabel("Account")
                Txt(session.account.name, T.title)
                val login = session.login
                val expiry = login.expiresAt?.let { "until ${dateFmt.format(Date(it))}" } ?: "no expiry date"
                Txt("${login.host.removePrefix("http://")} · $expiry · ${login.maxConnections} stream${if (login.maxConnections == 1) "" else "s"}", T.body, color = C.inkMuted)
                Txt(login.lastSyncAt?.let { "Last synced ${timeFmt.format(Date(it))}" } ?: "Not synced yet", T.caption, color = C.inkFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                    TvButton(
                        text = if (sync is SyncState.Running) sync.message else "Sync now",
                        icon = Icons.sync,
                        enabled = sync !is SyncState.Running,
                        modifier = Modifier.focusRequester(first),
                        onClick = onSync,
                    )
                    if (hidden > 0) TvButton("Show hidden categories ($hidden)", onClick = live::unhideAll)
                }
                if (sync is SyncState.Failed) Txt(sync.message, T.caption, color = C.danger, maxLines = 2)
            }

            Card {
                SectionLabel("Player")
                Txt("Some providers treat unknown players differently. If channels keep dropping, try the VLC identity.", T.body, color = C.inkMuted, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                    Prefs.Agent.entries.forEach { a ->
                        TvButton(
                            text = (if (a == agent) "✓ " else "") + "Identify as ${a.label}",
                            primary = a == agent,
                            onClick = { app.prefs.setAgent(a) },
                        )
                    }
                }
            }

            Card {
                SectionLabel("App")
                Txt("Restv ${app.updater.currentVersionName}", T.title)
                val (line, color) = when (val u = update) {
                    UpdateState.Idle -> "Updates come from GitHub releases." to C.inkMuted
                    UpdateState.Checking -> "Checking…" to C.inkMuted
                    UpdateState.UpToDate -> "You're on the latest version." to C.success
                    is UpdateState.Available -> "Version ${u.release.name.removePrefix("v")} is ready to install." to C.accent
                    is UpdateState.Downloading -> "Downloading · ${(u.fraction * 100).toInt()}%" to C.accent
                    is UpdateState.Error -> u.message to C.danger
                }
                Txt(line, T.body, color = color)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
                    when (val u = update) {
                        is UpdateState.Available -> TvButton("Update now", primary = true, icon = Icons.download) { installUpdate(u.release) }
                        is UpdateState.Downloading -> TvButton("Downloading…", enabled = false) {}
                        else -> TvButton("Check for updates", onClick = ::checkUpdate)
                    }
                }
                if (app.updater.needsInstallPermission() && update is UpdateState.Available) {
                    Txt("First time only: allow Restv to install apps, then press Update again.", T.caption, color = C.inkFaint, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(640.dp)
            .clip(RoundedCornerShape(S.radiusLg))
            .background(C.bg100)
            .padding(S.s4),
        verticalArrangement = Arrangement.spacedBy(S.s2),
    ) { content() }
}
