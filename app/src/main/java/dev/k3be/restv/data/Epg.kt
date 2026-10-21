package dev.k3be.restv.data

import android.util.Base64
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.data.xtream.XtreamClient
import dev.k3be.restv.data.xtream.long
import dev.k3be.restv.data.xtream.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

data class Programme(val title: String, val description: String, val start: Long, val end: Long) {
    fun progress(now: Long = System.currentTimeMillis()): Float =
        if (end <= start) 0f else ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

data class NowNext(val now: Programme?, val next: Programme?)

/** Now/next per channel from the panel's short EPG, cached for a few minutes. External XMLTV merging comes later. */
class EpgCache(private val xtream: XtreamClient) {
    private data class Entry(val at: Long, val value: NowNext)
    private val cache = ConcurrentHashMap<Long, Entry>()

    fun peek(streamId: Long): NowNext? = cache[streamId]?.value

    fun clear() = cache.clear()

    suspend fun nowNext(login: LoginEntity, streamId: Long): NowNext {
        val now = System.currentTimeMillis()
        cache[streamId]?.let { e ->
            val stillCurrent = e.value.now?.let { it.end > now } ?: (now - e.at < 120_000)
            if (stillCurrent && now - e.at < 10 * 60_000) return e.value
        }
        val result = runCatching { fetch(login, streamId) }.getOrElse { NowNext(null, null) }
        cache[streamId] = Entry(now, result)
        return result
    }

    private suspend fun fetch(login: LoginEntity, streamId: Long): NowNext {
        val root = xtream.shortEpg(login, streamId, 4) ?: return NowNext(null, null)
        val listings = (root["epg_listings"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val now = System.currentTimeMillis()
        val programmes = listings.mapNotNull { o ->
            val start = o.long("start_timestamp")?.times(1000) ?: return@mapNotNull null
            val end = (o.long("stop_timestamp") ?: o.long("end_timestamp"))?.times(1000) ?: return@mapNotNull null
            Programme(decode(o.str("title")), decode(o.str("description")), start, end)
        }.filter { it.end > now }.sortedBy { it.start }
        val current = programmes.firstOrNull { it.start <= now }
        val next = programmes.firstOrNull { it.start > now }
        return NowNext(current, next)
    }

    private fun decode(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s).trim()
    }
}
