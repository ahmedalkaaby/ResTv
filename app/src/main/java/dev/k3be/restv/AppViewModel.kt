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

/** Bump when VodNaming changes how saved titles should be parsed. */
const val VOD_PARSER = 2

/** How old the downloaded catalogue may get before it refreshes on its own. */
const val AUTO_SYNC_MS = 48 * 3_600_000L

data class Session(val account: AccountEntity, val login: LoginEntity)

sealed interface Route {
    data object Loading : Route
    data object Onboarding : Route
    data object Live : Route
    data object Home : Route
    data object Settings : Route
    data object Search : Route
    data object Matches : Route
    data class Player(val channels: List<ChannelRow>, val index: Int) : Route
    data object Movies : Route
    data object Series : Route
    data class MovieDetail(val id: Long) : Route
    data class SeriesDetail(val id: Long) : Route
    /** "See all" for a rail or category. */
    data class VodGrid(val kind: String, val railId: String, val title: String) : Route
    data class VodPlayer(val queue: List<PlayItem>, val index: Int, val fromStart: Boolean = false) : Route
}

/** One movie or episode to play. [kind] is "movie" or "episode". */
data class PlayItem(
    val kind: String,
    val id: Long,
    val seriesId: Long,
    val season: Int,
    val episode: Int,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val ext: String,
    val durationMs: Long = 0,
)

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String, val fraction: Float? = null) : SyncState
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

    /** Screens opened on top of the current tab (details, player), newest last. */
    private val stack = ArrayList<Route>()

    init {
        viewModelScope.launch {
            val account = app.db.accounts().lastUsed()
            val login = account?.let { app.db.logins().firstFor(it.id) }
            if (account == null || login == null) {
                _route.value = Route.Onboarding
            } else {
                app.db.accounts().touch(account.id, System.currentTimeMillis())
                _session.value = Session(account, login)
                _route.value = Route.Home
                if (login.lastSyncAt == null) syncNow()
                // A newer name parser (source tags like "AR SUBS") re-reads the catalogue once, in the background.
                else if (app.prefs.vodParser() < VOD_PARSER && app.db.vod().countMovies(login.id) + app.db.vod().countSeries(login.id) > 0) syncVodNow()
            }
        }
        // Channels, movies and series refresh themselves every 48 hours: checked at start and hourly while open.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val login = _session.value?.login
                val last = login?.lastSyncAt
                if (login != null && last != null && System.currentTimeMillis() - last > AUTO_SYNC_MS && _sync.value !is SyncState.Running) {
                    runSync(login)
                }
                kotlinx.coroutines.delay(60 * 60_000L)
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

    fun isTab(r: Route) = r is Route.Home || r is Route.Live || r is Route.Movies || r is Route.Series || r is Route.Search ||
        r is Route.Matches || r is Route.Settings

    fun go(route: Route) {
        if (isTab(route)) {
            stack.clear()
        } else {
            val current = _route.value
            // The next episode replaces the player rather than stacking another one.
            if (!(route is Route.VodPlayer && current is Route.VodPlayer)) stack += current
        }
        _route.value = route
    }

    /** Back from a screen opened on top of a tab (details, players): returns to it. False on a tab itself. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        _route.value = stack.removeAt(stack.lastIndex)
        return true
    }

    fun syncVodNow(first: String = "movie") {
        val login = _session.value?.login ?: return
        if (_sync.value is SyncState.Running) return
        viewModelScope.launch {
            try {
                app.sync.syncVod(login, first) { msg, f -> _sync.value = SyncState.Running(msg, f) }
                app.prefs.setVodParser(VOD_PARSER)
                _sync.value = SyncState.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Any failure, even an Error from a parser, ends as a message instead of closing the app.
                _sync.value = SyncState.Failed("Couldn't download movies and series: ${e.message ?: e.javaClass.simpleName}")
            }
        }
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
                if (_sync.value !is SyncState.Failed) _route.value = Route.Home
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
            app.epg.clear()
            app.sync.syncVod(login) { msg, f -> _sync.value = SyncState.Running(msg, f) }
            app.prefs.setVodParser(VOD_PARSER)
            _sync.value = SyncState.Idle
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _sync.value = SyncState.Failed(e.message ?: "Sync failed")
        }
    }
}
