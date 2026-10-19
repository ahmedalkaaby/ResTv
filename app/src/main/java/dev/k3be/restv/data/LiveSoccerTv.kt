package dev.k3be.restv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Reads one day of football from LiveSoccerTV's schedule page, straight from the TV.
 * Fetching from home matters: the site shows the channels of the viewer's country, and it
 * blocks datacenter servers. The parser follows links (/competitions/, /match/, /channels/)
 * rather than exact CSS classes, so most redesigns don't break it.
 */
class LiveSoccerTv(private val http: OkHttpClient) {

    class BlockedException(message: String) : IOException(message)

    suspend fun fetchDay(date: LocalDate): List<Match> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://www.livesoccertv.com/schedules/$date/")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-GB,en;q=0.9")
            .build()
        val html = http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 403 || body.contains("<title>Just a moment", ignoreCase = true)) {
                throw BlockedException("LiveSoccerTV asked for a browser check")
            }
            if (!resp.isSuccessful) throw IOException("LiveSoccerTV answered ${resp.code}")
            body
        }
        parse(html, date)
    }

    fun parse(html: String, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Match> {
        val doc = Jsoup.parse(html, "https://www.livesoccertv.com/")
        val out = ArrayList<Match>()
        var competition: String? = null
        for (tr in doc.select("tr")) {
            val matchLink = tr.selectFirst("a[href*=/match/]")
            val compLink = tr.selectFirst("a[href*=/competitions/]")
            if (compLink != null && matchLink == null) {
                competition = clean(compLink.text())
                continue
            }
            if (matchLink == null) continue
            val teams = clean(matchLink.text()).split(Regex("""\s+(?:vs\.?|v)\s+""", RegexOption.IGNORE_CASE))
            if (teams.size != 2 || teams.any { it.isBlank() }) continue
            val (home, away) = teams.map { it.trim() }

            val channels = LinkedHashSet<String>()
            for (a in tr.select("a[href*=/channels/]")) {
                val name = channelName(a.attr("title").ifBlank { a.text() })
                if (name.isNotEmpty()) channels += name
            }
            out += Match(
                id = md5("$date|$home|$away").take(12),
                kickoff = kickoff(tr, date, zone),
                competition = competition,
                home = home,
                away = away,
                broadcasters = channels.toList(),
            )
        }
        return out.sortedBy { it.kickoff ?: Long.MAX_VALUE }
    }

    /** Exact unix time from a data attribute when present, else "HH:MM" in the TV's own timezone. */
    private fun kickoff(tr: Element, date: LocalDate, zone: ZoneId): Long? {
        for (el in tr.select("[dv], [data-time], [data-timestamp]")) {
            val raw = el.attr("dv").ifBlank { el.attr("data-time") }.ifBlank { el.attr("data-timestamp") }
            val n = raw.toLongOrNull() ?: continue
            return if (raw.length > 11) n else n * 1000
        }
        val m = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)""").find(tr.text()) ?: return null
        val t = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        return date.atTime(t).atZone(zone).toInstant().toEpochMilli()
    }

    /** "beIN Sports English 2 (live stream available)" → "beIN Sports English 2"; drops country lists too. */
    private fun channelName(s: String): String =
        clean(s).replace(Regex("""\s*\([^)]*\)?\s*$"""), "").replace(Regex("""\s*\([^)]*\)"""), "").trim()

    private fun clean(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}
