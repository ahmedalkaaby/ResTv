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

    fun lastCategory(loginId: Long): String? = sp.getString("lastCat_$loginId", null)

    fun setLastCategory(loginId: Long, categoryId: String) {
        sp.edit().putString("lastCat_$loginId", categoryId).apply()
    }

    fun setAgent(a: Agent) {
        sp.edit().putString("agent", a.name).apply()
        _agent.value = a
    }
}
