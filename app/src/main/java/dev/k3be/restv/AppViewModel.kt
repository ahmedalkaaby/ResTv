package dev.k3be.restv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.k3be.restv.data.db.AccountEntity
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.data.xtream.XtreamClient
import dev.k3be.restv.ui.live.ChannelRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class Session(val account: AccountEntity, val login: LoginEntity)

sealed interface Route {
    data object Loading : Route
    data object Onboarding : Route
    data object Live : Route
    data object Settings : Route
    data class Player(val channels: List<ChannelRow>, val index: Int) : Route
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RestvApp

    private val _route = MutableStateFlow<Route>(Route.Loading)
    val route: StateFlow<Route> = _route

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync

    /** The screen to return to when leaving the player or settings. */
    private var mainRoute: Route = Route.Live

    init {
        viewModelScope.launch {
            val account = app.db.accounts().lastUsed()
            val login = account?.let { app.db.logins().firstFor(it.id) }
            if (account == null || login == null) {
                _route.value = Route.Onboarding
            } else {
                app.db.accounts().touch(account.id, System.currentTimeMillis())
                _session.value = Session(account, login)
                _route.value = Route.Live
                if (login.lastSyncAt == null) syncNow()
            }
        }
        viewModelScope.launch {
            // Keep the session's login fresh (expiry, last sync) after syncs.
            _session.map { it?.login?.id }.distinctUntilChanged().collectLatest { id ->
                if (id != null) app.db.logins().observe(id).collect { l ->
                    if (l != null && l != _session.value?.login) _session.value = _session.value?.copy(login = l)
                }
            }
        }
    }

    fun go(route: Route) {
        if (route is Route.Live) mainRoute = route
        _route.value = route
    }

    fun back(): Boolean = when (_route.value) {
        is Route.Player, Route.Settings -> { _route.value = mainRoute; true }
        else -> false
    }

    fun connect(name: String, host: String, username: String, password: String) {
        if (_sync.value is SyncState.Running) return
        viewModelScope.launch {
            _sync.value = SyncState.Running("Connecting")
            try {
                val probe = LoginEntity(accountId = 0, host = XtreamClient.normalizeHost(host), username = username.trim(), password = password)
                app.xtream.auth(probe)
                val now = System.currentTimeMillis()
                val accountId = app.db.accounts().insert(AccountEntity(name = name.trim().ifEmpty { "Me" }, lastUsedAt = now))
                val loginId = app.db.logins().insert(probe.copy(accountId = accountId))
                val account = AccountEntity(accountId, name.trim().ifEmpty { "Me" }, now)
                val login = probe.copy(id = loginId, accountId = accountId)
                _session.value = Session(account, login)
                runSync(login)
                if (_sync.value !is SyncState.Failed) _route.value = Route.Live
            } catch (e: Exception) {
                _sync.value = SyncState.Failed(e.message ?: "Couldn't connect")
            }
        }
    }

    fun syncNow() {
        val login = _session.value?.login ?: return
        if (_sync.value is SyncState.Running) return
        viewModelScope.launch { runSync(login) }
    }

    private suspend fun runSync(login: LoginEntity) {
        try {
            _sync.value = SyncState.Running("Starting")
            app.sync.syncLive(login) { msg -> _sync.value = SyncState.Running(msg) }
            _sync.value = SyncState.Idle
        } catch (e: Exception) {
            _sync.value = SyncState.Failed(e.message ?: "Sync failed")
        }
    }
}
