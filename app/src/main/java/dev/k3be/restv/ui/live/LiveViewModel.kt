package dev.k3be.restv.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.k3be.restv.RestvApp
import dev.k3be.restv.data.NowNext
import dev.k3be.restv.data.db.CategoryPrefEntity
import dev.k3be.restv.data.db.ChannelEntity
import dev.k3be.restv.data.db.FavoriteEntity
import dev.k3be.restv.data.db.LoginEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CatItem(
    val id: String,
    val name: String,
    val groupLabel: String,
    val count: Int,
    val pinned: Boolean = false,
    val special: Boolean = false,
)

/**
 * One row in the channel list. "Sports 1 HD", "Sports 1 FHD" and "Sports 1 4K" become one row;
 * [main] is the best quality, [others] the rest, best first (used for automatic fallback).
 */
data class ChannelRow(val main: ChannelEntity, val others: List<ChannelEntity>) {
    val streamId: Long get() = main.streamId
}

data class Preview(
    val row: ChannelRow,
    val nowNext: NowNext?,
)

/** Folds HD/FHD/4K copies of a channel into one row. Input must be sorted best quality first. */
fun groupVariants(list: List<ChannelEntity>): List<ChannelRow> {
    val groups = LinkedHashMap<String, MutableList<ChannelEntity>>()
    list.forEach { ch -> groups.getOrPut(ch.categoryId + "|" + ch.variantKey.ifEmpty { "id${ch.streamId}" }) { mutableListOf() } += ch }
    return groups.values.map { g ->
        val sorted = g.sortedByDescending { it.quality }
        ChannelRow(sorted.first(), sorted.drop(1))
    }
}

const val FAV_ID = "__fav"
const val UHD_ID = "__4k"

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(
    private val app: RestvApp,
    private val accountId: Long,
    private val login: LoginEntity,
) : ViewModel() {
    private val loginId = login.id
    private val live = app.db.live()
    private val prefs = app.db.prefs()

    private val favorites = live.observeFavorites(accountId, loginId)
    private val uhd = live.observe4k(loginId)

    val favoriteIds: StateFlow<Set<Long>> = prefs.observeFavoriteIds(accountId, loginId)
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Normal categories, ordered by the user's saved order, else grouped by country. */
    private val ordered: StateFlow<List<CatItem>> = combine(
        live.observeCategories(loginId),
        prefs.observeCategoryPrefs(accountId, loginId),
        live.observeCounts(loginId),
    ) { cats, prefList, counts ->
        val prefMap = prefList.associateBy { it.categoryId }
        val countMap = counts.associate { it.categoryId to it.n }
        val groupFirst = LinkedHashMap<String, Int>()
        cats.forEach { groupFirst.putIfAbsent(it.groupKey, it.providerOrder) }
        val defaultIndex = cats
            .sortedWith(compareBy({ groupFirst[it.groupKey] ?: 0 }, { it.providerOrder }))
            .mapIndexed { i, c -> c.categoryId to i }
            .toMap()
        cats.asSequence()
            .filter { prefMap[it.categoryId]?.hidden != true }
            .filter { (countMap[it.categoryId] ?: 0) > 0 }
            .map { c ->
                val p = prefMap[c.categoryId]
                Triple(
                    CatItem(c.categoryId, c.name, c.groupLabel, countMap[c.categoryId] ?: 0, pinned = p?.pinned == true),
                    p?.position ?: (100_000 + (defaultIndex[c.categoryId] ?: 0)),
                    p?.pinned == true,
                )
            }
            .sortedWith(compareByDescending<Triple<CatItem, Int, Boolean>> { it.third }.thenBy { it.second })
            .map { it.first }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hiddenCount: StateFlow<Int> = prefs.observeCategoryPrefs(accountId, loginId)
        .map { list -> list.count { it.hidden } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val specials: StateFlow<List<CatItem>> = combine(favorites, uhd) { f, u ->
        buildList {
            add(CatItem(FAV_ID, "Favourites", "", f.size, special = true))
            if (u.isNotEmpty()) add(CatItem(UHD_ID, "4K channels", "", u.size, special = true))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Move mode keeps its own copy of the list until the user drops or cancels.
    private val _movingId = MutableStateFlow<String?>(null)
    val movingId: StateFlow<String?> = _movingId
    private val _movingList = MutableStateFlow<List<CatItem>?>(null)

    val categories: StateFlow<List<CatItem>> = combine(specials, ordered, _movingList) { s, o, m -> s + (m ?: o) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Highlighted category: follows focus instantly. */
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    /** Channels load only once focus rests on a category, so scrolling the list stays smooth. */
    val channels: StateFlow<List<ChannelRow>> = _selected
        .debounce(220)
        .distinctUntilChanged()
        .flatMapLatest { id ->
            when (id) {
                null -> flowOf(emptyList())
                FAV_ID -> favorites.map { list -> list.map { ChannelRow(it, emptyList()) } }
                UHD_ID -> uhd.map { list -> list.map { ChannelRow(it, emptyList()) } }
                else -> live.observeChannels(loginId, id).map { groupVariants(it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview

    /** "Now" programme titles for rows near the focused one. */
    private val _nowTitles = MutableStateFlow<Map<Long, String>>(emptyMap())
    val nowTitles: StateFlow<Map<Long, String>> = _nowTitles

    /** Accordion: only one country group is open at a time; Pinned and the specials are always open. */
    private val _openGroup = MutableStateFlow(app.prefs.openGroup(loginId))
    val openGroup: StateFlow<String?> = _openGroup

    fun toggleGroup(label: String) {
        val next = if (_openGroup.value == label) null else label
        _openGroup.value = next
        app.prefs.setOpenGroup(loginId, next)
    }

    /** Search survives a trip to the player and back. */
    val searchQuery = MutableStateFlow("")
    var lastSearchStreamId: Long? = null

    /** Where the category list was scrolled, so it comes back in the same place. */
    var categoryScrollIndex: Int = 0

    /** Remembered so focus returns to the same channel after the player closes. */
    var lastFocusedStreamId: Long? = null
        private set

    private var epgJob: Job? = null

    init {
        viewModelScope.launch {
            // Start where the user left off last time; else Favourites if any; else the first category.
            categories.collect { list ->
                if (_selected.value == null && list.isNotEmpty()) {
                    val saved = app.prefs.lastCategory(loginId)
                    val fav = list.firstOrNull { it.id == FAV_ID }
                    _selected.value = when {
                        saved != null && list.any { it.id == saved } -> saved
                        fav != null && fav.count > 0 -> FAV_ID
                        else -> list.firstOrNull { !it.special }?.id ?: list.first().id
                    }
                    if (_openGroup.value == null) {
                        list.firstOrNull { it.id == _selected.value && !it.special && !it.pinned }?.let {
                            _openGroup.value = it.groupLabel
                        }
                    }
                }
            }
        }
    }

    fun select(id: String) {
        if (_movingId.value != null) return
        _selected.value = id
        if (id != FAV_ID && id != UHD_ID) app.prefs.setLastCategory(loginId, id)
    }

    fun focusChannel(row: ChannelRow, neighbours: List<ChannelRow>) {
        lastFocusedStreamId = row.streamId
        _preview.value = Preview(row, app.epg.peek(row.streamId))
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            delay(300) // wait for focus to rest before touching the network
            val nn = app.epg.nowNext(login, row.streamId)
            if (_preview.value?.row?.streamId == row.streamId) _preview.value = Preview(row, nn)
            nn.now?.title?.takeIf { it.isNotBlank() }?.let { _nowTitles.value = _nowTitles.value + (row.streamId to it) }
            // Then fill in the rows around it, one request at a time.
            for (n in neighbours) {
                if (n.streamId in _nowTitles.value) continue
                val title = app.epg.nowNext(login, n.streamId).now?.title
                if (!title.isNullOrBlank()) _nowTitles.value = _nowTitles.value + (n.streamId to title)
            }
        }
    }

    fun toggleFavorite(ch: ChannelEntity) {
        viewModelScope.launch {
            if (ch.streamId in favoriteIds.value) prefs.removeFavorite(accountId, loginId, ch.streamId)
            else prefs.addFavorite(FavoriteEntity(accountId, loginId, ch.streamId, System.currentTimeMillis()))
        }
    }

    // ---- Category manager actions ----

    fun startMove(id: String) {
        if (categories.value.firstOrNull { it.id == id }?.special == true) return
        _movingList.value = ordered.value
        _movingId.value = id
    }

    fun moveBy(delta: Int) {
        val id = _movingId.value ?: return
        val list = _movingList.value?.toMutableList() ?: return
        val i = list.indexOfFirst { it.id == id }
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        val item = list.removeAt(i)
        list.add(j, item)
        _movingList.value = list
    }

    fun dropMove() {
        val list = _movingList.value ?: return cancelMove()
        viewModelScope.launch {
            savePositions(list)
            _movingId.value = null
            _movingList.value = null
        }
    }

    fun cancelMove() {
        _movingId.value = null
        _movingList.value = null
    }

    fun togglePin(id: String) {
        val list = ordered.value
        val item = list.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { savePositions(list, pinnedOverride = id to !item.pinned) }
    }

    fun hide(id: String) {
        val list = ordered.value
        viewModelScope.launch {
            savePositions(list, hiddenId = id)
            if (_selected.value == id) _selected.value = list.firstOrNull { it.id != id }?.id
        }
    }

    fun unhideAll() {
        viewModelScope.launch { prefs.unhideAll(accountId, loginId) }
    }

    private suspend fun savePositions(
        list: List<CatItem>,
        pinnedOverride: Pair<String, Boolean>? = null,
        hiddenId: String? = null,
    ) {
        prefs.upsertCategoryPrefs(
            list.mapIndexed { index, c ->
                CategoryPrefEntity(
                    accountId = accountId,
                    loginId = loginId,
                    categoryId = c.id,
                    position = index,
                    hidden = c.id == hiddenId,
                    pinned = if (pinnedOverride?.first == c.id) pinnedOverride.second else c.pinned,
                )
            }
        )
    }
}
