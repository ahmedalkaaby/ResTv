package dev.k3be.restv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small device-level settings. Personal data lives in the database per account. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("restv", Context.MODE_PRIVATE)

    enum class Agent(val label: String, val value: String) {
        DEFAULT("Restv", "Restv/1.0 (Android TV)"),
        VLC("VLC", "VLC/3.0.20 LibVLC/3.0.20"),
    }

    private val _agent = MutableStateFlow(
        runCatching { Agent.valueOf(sp.getString("agent", Agent.DEFAULT.name)!!) }.getOrDefault(Agent.DEFAULT)
    )
    val agent: StateFlow<Agent> = _agent

    /** Version of the movie/series name parser the saved catalogue was built with. */
    fun vodParser(): Int = sp.getInt("vodParser", 0)
    fun setVodParser(v: Int) { sp.edit().putInt("vodParser", v).apply() }

    /** The version (language/quality copy) you picked for a title, so it stays picked. */
    fun chosenVersion(kind: String, titleKey: String): Long? = sp.getLong("ver_${kind}_$titleKey", -1L).takeIf { it >= 0 }
    fun setChosenVersion(kind: String, titleKey: String, id: Long) { sp.edit().putLong("ver_${kind}_$titleKey", id).apply() }

    /** SubDL API key for Arabic subtitles. */
    fun subdlKey(): String? = sp.getString("subdlKey", null)?.trim()?.takeIf { it.isNotEmpty() }
    fun setSubdlKey(k: String) { sp.edit().putString("subdlKey", k.trim()).apply() }

    /** Subtitle timing per title (ms; positive shows them later); a series shares one value. */
    fun subDelay(key: String): Long = sp.getLong("subDelay_$key", 0L)
    fun setSubDelay(key: String, ms: Long) { sp.edit().putLong("subDelay_$key", ms).apply() }

    /** Intro learned for a series: where it starts and ends (ms). */
    fun intro(seriesId: Long): Pair<Long, Long>? = sp.getString("intro_$seriesId", null)?.split(',')
        ?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun setIntro(seriesId: Long, start: Long, end: Long) { sp.edit().putString("intro_$seriesId", "$start,$end").apply() }

    /** How long a series' end credits run (ms), learned from when you move on. */
    fun credits(seriesId: Long): Long = sp.getLong("credits_$seriesId", 40_000L)
    fun setCredits(seriesId: Long, ms: Long) { sp.edit().putLong("credits_$seriesId", ms.coerceIn(10_000L, 8 * 60_000L)).apply() }

    fun audioToken(): String? = sp.getString("audioToken", null)?.takeIf { it.isNotBlank() }

    fun setAudioToken(token: String) {
        sp.edit().putString("audioToken", token.trim()).apply()
    }

    /** Commentary delay remembered per channel + audio source pair. */
    fun commentaryDelayMs(streamId: Long, sourceId: String): Long? =
        sp.getLong("delay_${streamId}_$sourceId", -1L).takeIf { it >= 0 }

    fun setCommentaryDelayMs(streamId: Long, sourceId: String, ms: Long) {
        sp.edit().putLong("delay_${streamId}_$sourceId", ms).apply()
    }

    /** Last delay used with this source on any channel: a good starting guess. */
    fun lastDelayMs(sourceId: String): Long? = sp.getLong("delay_last_$sourceId", -1L).takeIf { it >= 0 }

    fun setLastDelayMs(sourceId: String, ms: Long) {
        sp.edit().putLong("delay_last_$sourceId", ms).apply()
    }

    fun channelSoundLevel(): Float = sp.getFloat("channelSound", 0f)

    fun setChannelSoundLevel(level: Float) {
        sp.edit().putFloat("channelSound", level).apply()
    }

    private val recentFlows = HashMap<Long, MutableStateFlow<List<Long>>>()

    /** Recently watched channel ids, newest first (max 30). */
    fun recent(loginId: Long): StateFlow<List<Long>> = recentFlows.getOrPut(loginId) {
        MutableStateFlow(
            sp.getString("recent_$loginId", "")!!.split(',').mapNotNull { it.toLongOrNull() }
        )
    }

    fun addRecent(loginId: Long, streamId: Long) {
        val flow = recent(loginId) as MutableStateFlow
        val next = (listOf(streamId) + flow.value.filter { it != streamId }).take(30)
        flow.value = next
        sp.edit().putString("recent_$loginId", next.joinToString(",")).apply()
    }

    fun matchesUrl(): String? = sp.getString("matchesUrl", null)?.takeIf { it.isNotBlank() }
    fun matchesKey(): String? = sp.getString("matchesKey", null)?.takeIf { it.isNotBlank() }

    fun setMatchesServer(url: String, key: String) {
        sp.edit().putString("matchesUrl", url.trim()).putString("matchesKey", key.trim()).apply()
    }

    fun openGroup(loginId: Long): String? = sp.getString("openGroup_$loginId", null)

    fun setOpenGroup(loginId: Long, label: String?) {
        sp.edit().apply { if (label == null) remove("openGroup_$loginId") else putString("openGroup_$loginId", label) }.apply()
    }

    fun lastCategory(loginId: Long): String? = sp.getString("lastCat_$loginId", null)

    fun setLastCategory(loginId: Long, categoryId: String) {
        sp.edit().putString("lastCat_$loginId", categoryId).apply()
    }

    fun setAgent(a: Agent) {
        sp.edit().putString("agent", a.name).apply()
        _agent.value = a
    }
}
