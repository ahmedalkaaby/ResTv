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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

data class Preview(
    val channel: ChannelEntity,
    val nowNext: NowNext?,
    val variants: List<ChannelEntity>,
)

const val FAV_ID = "__fav"
const val UHD_ID = "__4k"

@OptIn(ExperimentalCoroutinesApi::class)
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

    /** Normal (non-special) categories, ordered by the user's saved order, else grouped by country. */
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

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    val channels: StateFlow<List<ChannelEntity>> = _selected.flatMapLatest { id ->
        when (id) {
            null -> kotlinx.coroutines.flow.flowOf(emptyList())
            FAV_ID -> favorites
            UHD_ID -> uhd
            else -> live.observeChannels(loginId, id)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview

    /** Remembered so focus returns to the same channel after the player closes. */
    var lastFocusedStreamId: Long? = null
        private set

    init {
        viewModelScope.launch {
            // Pick a sensible starting category once data arrives: Favourites if any, else the first real one.
            categories.collect { list ->
                if (_selected.value == null && list.isNotEmpty()) {
                    val fav = list.firstOrNull { it.id == FAV_ID }
                    _selected.value = if (fav != null && fav.count > 0) FAV_ID else list.firstOrNull { !it.special }?.id ?: list.first().id
                }
            }
        }
    }

    fun select(id: String) {
        if (_movingId.value == null) _selected.value = id
    }

    fun focusChannel(ch: ChannelEntity) {
        lastFocusedStreamId = ch.streamId
        _preview.value = Preview(ch, app.epg.peek(ch.streamId), _preview.value?.takeIf { it.channel.variantKey == ch.variantKey }?.variants ?: emptyList())
        viewModelScope.launch {
            val variants = live.variants(loginId, ch.variantKey)
            val nn = app.epg.nowNext(login, ch.streamId)
            if (_preview.value?.channel?.streamId == ch.streamId) _preview.value = Preview(ch, nn, variants)
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
