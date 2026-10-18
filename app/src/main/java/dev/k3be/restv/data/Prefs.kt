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
