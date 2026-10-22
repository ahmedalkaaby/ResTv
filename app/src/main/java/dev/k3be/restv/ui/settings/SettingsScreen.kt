package dev.k3be.restv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.SyncState
import dev.k3be.restv.data.Prefs
import dev.k3be.restv.ui.components.FocusRow
import dev.k3be.restv.ui.components.Icons
import dev.k3be.restv.ui.components.TvButton
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.live.NavScaffold
import dev.k3be.restv.ui.live.SidebarItem
import dev.k3be.restv.ui.live.rememberLiveViewModel
import dev.k3be.restv.ui.onboarding.TvTextField
import dev.k3be.restv.ui.player.displayHdr
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

/**
 * How focus moves on this page: Right from the list goes to the pane's main control ([primary]);
 * Left from a pane's first control returns to the list ([back]). Explicit, so the floating top bar
 * never steals the move.
 */
private class PaneFocus(val primary: FocusRequester, val back: Modifier)

private val LocalPane = staticCompositionLocalOf { PaneFocus(FocusRequester(), Modifier) }

private enum class Section(val label: String) {
    Updates("Updates"),
    Account("Account & library"),
    Subtitles("Arabic subtitles"),
    Artwork("Artwork & logos"),
    Commentary("Commentary audio"),
    Player("Player"),
    Matches("Matches server"),
}

/** Sections on the left, the chosen one on the right: nothing to scroll through, inputs only on "Change". */
@Composable
fun SettingsScreen(session: Session, sync: SyncState, onSync: () -> Unit, onNavigate: (SidebarItem) -> Unit) {
    val app = LocalContext.current.applicationContext as RestvApp
    var section by rememberSaveable { mutableStateOf(Section.Updates) }
    val rows = remember { Section.entries.associateWith { FocusRequester() } }
    val primary = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rows.getValue(Section.Updates).requestFocus() } }
    val pane = PaneFocus(primary, Modifier.focusProperties { left = rows.getValue(section) })

    NavScaffold(active = SidebarItem.Settings, onSelect = onNavigate) {
        Column(Modifier.width(250.dp).fillMaxHeight().padding(top = S.safeY), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt("Settings", T.titleLg, modifier = Modifier.padding(start = 12.dp, bottom = S.s3))
            Section.entries.forEach { s ->
                FocusRow(
                    modifier = Modifier.focusRequester(rows.getValue(s)).onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                            runCatching { primary.requestFocus() }; true
                        } else false
                    },
                    selected = s == section,
                    height = 46.dp,
                    onFocused = { section = s },
                    onClick = { section = s },
                ) { _ -> Txt(s.label, T.label, color = if (s == section) C.ink else C.inkMuted, modifier = Modifier.padding(horizontal = 14.dp)) }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = S.s8, top = S.safeY + 46.dp, end = S.safeX),
            verticalArrangement = Arrangement.spacedBy(S.s3),
        ) {
            CompositionLocalProvider(LocalPane provides pane) { when (section) {
                Section.Updates -> UpdatesPane(app)
                Section.Account -> AccountPane(app, session, sync, onSync)
                Section.Subtitles -> SecretPane(
                    title = "Arabic subtitles · SubDL",
                    help = "Movies and episodes get Arabic subtitles automatically. Get a free key: sign up at subdl.com, then subdl.com/panel/api. In the player, ▲ picks another subtitle, its size and timing.",
                    label = "SubDL API key",
                    current = app.prefs.subdlKey(),
                    onSave = { app.prefs.setSubdlKey(it) },
                    check = { app.subdl.check(it) },
                )
                Section.Artwork -> SecretPane(
                    title = "Artwork & logos · TMDB",
                    help = "Wide artwork and each title's logo come from TMDB. Get a free key: sign up at themoviedb.org, then Settings → API → copy the “API Key”. Without one, the provider's own images are used.",
                    label = "TMDB API key",
                    current = app.prefs.tmdbKey(),
                    onSave = { app.prefs.setTmdbKey(it); app.art.reset() },
                    check = { app.art.check(it) },
                    extra = {
                        val logos = remember { app.art.logoCount() }
                        Txt(
                            "Logos saved so far: $logos" + (app.art.lastError?.let { " · last problem: $it" } ?: ""),
                            T.caption, color = if (app.art.lastError != null) C.danger else C.inkFaint, maxLines = 2,
                        )
                    },
                )
                Section.Commentary -> SecretPane(
                    title = "Commentary audio",
                    help = "Anis FM playlist · ${app.audioSources.list().size} sources. While watching a match, hold OK to pick one.",
                    label = "Playlist token",
                    current = app.prefs.audioToken() ?: app.audioSources.bundledToken,
                    onSave = { app.prefs.setAudioToken(it) },
                )
                Section.Player -> PlayerPane(app)
                Section.Matches -> MatchesPane(app)
            } }
        }
    }
}

@Composable
private fun UpdatesPane(app: RestvApp) {
    val pane = LocalPane.current
    val main = Modifier.focusRequester(pane.primary).then(pane.back)
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    fun check() {
        scope.launch {
            update = UpdateState.Checking
            update = try {
                app.updater.check()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: "Couldn't reach GitHub")
            }
        }
    }
    fun install(release: Updater.Release) {
        if (app.updater.needsInstallPermission()) { app.updater.openInstallPermissionSettings(); return }
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
    Txt("Restv ${app.updater.currentVersionName}", T.display)
    val (line, color) = when (val u = update) {
        UpdateState.Idle -> "Updates come from GitHub releases." to C.inkMuted
        UpdateState.Checking -> "Checking…" to C.inkMuted
        UpdateState.UpToDate -> "You're on the latest version." to C.success
        is UpdateState.Available -> "Version ${u.release.name.removePrefix("v")} is ready to install." to C.accent
        is UpdateState.Downloading -> "Downloading · ${(u.fraction * 100).toInt()}%" to C.accent
        is UpdateState.Error -> u.message to C.danger
    }
    Txt(line, T.bodyLg, color = color)
    Row(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
        when (val u = update) {
            is UpdateState.Available -> TvButton("Update now", primary = true, icon = Icons.download, modifier = main) { install(u.release) }
            is UpdateState.Downloading -> TvButton("Downloading…", enabled = false, modifier = main) {}
            else -> TvButton("Check for updates", primary = true, modifier = main, onClick = ::check)
        }
    }
    // The button changes as the check runs; keep focus on whichever one is showing.
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(update) { if (update != UpdateState.Idle) touched = true; if (touched) runCatching { pane.primary.requestFocus() } }
    if (app.updater.needsInstallPermission() && update is UpdateState.Available) {
        Txt("First time only: allow Restv to install apps, then press Update again.", T.caption, color = C.inkFaint, maxLines = 2)
    }
}

@Composable
private fun AccountPane(app: RestvApp, session: Session, sync: SyncState, onSync: () -> Unit) {
    val live = rememberLiveViewModel(session)
    val hidden by live.hiddenCount.collectAsStateWithLifecycle()
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    val login = session.login
    Txt(session.account.name, T.titleLg)
    val expiry = login.expiresAt?.let { "until ${dateFmt.format(Date(it))}" } ?: "no expiry date"
    Txt("${login.host.removePrefix("http://")} · $expiry · ${login.maxConnections} stream${if (login.maxConnections == 1) "" else "s"}", T.body, color = C.inkMuted)
    Txt(
        (login.lastSyncAt?.let { "Channels, movies and series updated ${timeFmt.format(Date(it))}" } ?: "Not synced yet") +
            " · updates itself every 48 hours",
        T.body, color = C.inkFaint, maxLines = 2,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
        TvButton(
            modifier = Modifier.focusRequester(LocalPane.current.primary).then(LocalPane.current.back),
            text = if (sync is SyncState.Running) sync.message else "Update now",
            icon = Icons.sync,
            enabled = sync !is SyncState.Running,
            onClick = onSync,
        )
        if (hidden > 0) TvButton("Show hidden categories ($hidden)", onClick = live::unhideAll)
    }
    if (sync is SyncState.Failed) Txt(sync.message, T.caption, color = C.danger, maxLines = 2)
}

private sealed interface KeyHealth {
    data object Unknown : KeyHealth
    data object Checking : KeyHealth
    data object Ok : KeyHealth
    data class Bad(val why: String) : KeyHealth
}

/**
 * A saved value shown as ••••abcd; the text field only appears after "Change". With [check], the key is
 * tested right after saving, and "Check" tests it again any time.
 */
@Composable
private fun SecretPane(
    title: String,
    help: String,
    label: String,
    current: String?,
    onSave: (String) -> Unit,
    check: (suspend (String) -> String?)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val pane = LocalPane.current
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    var editing by remember(title) { mutableStateOf(false) }
    var opened by remember(title) { mutableStateOf(false) }
    var value by remember(title) { mutableStateOf(current ?: "") }
    var saved by remember(title) { mutableStateOf(current) }
    var health by remember(title) { mutableStateOf<KeyHealth>(KeyHealth.Unknown) }
    fun runCheck() {
        val k = saved ?: return
        val c = check ?: return
        scope.launch {
            health = KeyHealth.Checking
            health = c(k)?.let { KeyHealth.Bad(it) } ?: KeyHealth.Ok
        }
    }
    LaunchedEffect(editing) {
        if (editing) { opened = true; runCatching { field.requestFocus() } }
        else if (opened) runCatching { pane.primary.requestFocus() }
    }
    Txt(title, T.titleLg)
    Txt(help, T.body, color = C.inkMuted, maxLines = 4)
    if (!editing) {
        Txt(saved?.let { "$label:  ••••••${it.takeLast(4)}" } ?: "$label: not set", T.bodyLg, color = if (saved == null) C.inkFaint else C.ink)
        when (val h = health) {
            KeyHealth.Unknown -> {}
            KeyHealth.Checking -> Txt("Checking the key…", T.body, color = C.inkMuted)
            KeyHealth.Ok -> Txt("✓ Working", T.label, color = C.success)
            is KeyHealth.Bad -> Txt("✗ ${h.why}", T.body, color = C.danger, maxLines = 3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
            TvButton(if (saved == null) "Add" else "Change", primary = saved == null, modifier = Modifier.focusRequester(pane.primary).then(pane.back)) { editing = true }
            if (check != null && saved != null) TvButton(if (health == KeyHealth.Checking) "Checking…" else "Check") { runCheck() }
        }
        extra?.invoke()
    } else {
        Column(Modifier.width(460.dp)) { TvTextField(label, value, { value = it }, Modifier.focusRequester(field).then(pane.back)) }
        Row(horizontalArrangement = Arrangement.spacedBy(S.s2), verticalAlignment = Alignment.CenterVertically) {
            TvButton("Save", primary = true, modifier = pane.back) {
                onSave(value)
                saved = value.trim().ifEmpty { null }
                editing = false
                // A new key is tested straight away.
                if (saved != null) runCheck() else health = KeyHealth.Unknown
            }
            TvButton("Cancel") { value = saved ?: ""; editing = false }
        }
    }
}

@Composable
private fun PlayerPane(app: RestvApp) {
    val agent by app.prefs.agent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Txt("Player", T.titleLg)
    Txt("Some providers treat unknown players differently. If channels keep dropping, try the VLC identity.", T.body, color = C.inkMuted, maxLines = 2)
    Row(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
        Prefs.Agent.entries.forEachIndexed { i, a ->
            TvButton(
                modifier = if (i == 0) Modifier.focusRequester(LocalPane.current.primary).then(LocalPane.current.back) else Modifier,
                text = (if (a == agent) "✓ " else "") + "Identify as ${a.label}",
                primary = a == agent,
                onClick = { app.prefs.setAgent(a) },
            )
        }
    }
    Txt("This TV reports: ${displayHdr(context) ?: "no HDR formats"}", T.body, color = C.inkFaint, modifier = Modifier.padding(top = S.s4))
}

@Composable
private fun MatchesPane(app: RestvApp) {
    val pane = LocalPane.current
    val field = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) { opened = true; runCatching { field.requestFocus() } }
        else if (opened) runCatching { pane.primary.requestFocus() }
    }
    var url by remember { mutableStateOf(app.prefs.matchesUrl() ?: "") }
    var key by remember { mutableStateOf(app.prefs.matchesKey() ?: "") }
    Txt("Matches server (optional)", T.titleLg)
    Txt("Matches load directly on this TV. Your server is only used if that fails.", T.body, color = C.inkMuted, maxLines = 2)
    if (!editing) {
        Txt(url.ifBlank { "Not set" }, T.bodyLg, color = if (url.isBlank()) C.inkFaint else C.ink)
        TvButton(if (url.isBlank()) "Add" else "Change", modifier = Modifier.focusRequester(pane.primary).then(pane.back)) { editing = true }
    } else {
        Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(S.s2)) {
            TvTextField("Server URL", url, { url = it }, Modifier.focusRequester(field).then(pane.back))
            TvTextField("Key", key, { key = it }, pane.back, password = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(S.s2)) {
            TvButton("Save", primary = true, modifier = pane.back) { app.prefs.setMatchesServer(url, key); editing = false }
            TvButton("Cancel") { url = app.prefs.matchesUrl() ?: ""; key = app.prefs.matchesKey() ?: ""; editing = false }
        }
    }
}
