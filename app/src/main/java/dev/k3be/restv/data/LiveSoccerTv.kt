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

    /** A match page: every country's broadcasters (not just the viewer's) and the team crests. */
    data class Detail(
        val coverage: List<Pair<String, String>>,
        val homeLogo: String?,
        val awayLogo: String?,
    )

    suspend fun fetchDay(date: LocalDate): List<Match> = withContext(Dispatchers.IO) {
        parse(get("https://www.livesoccertv.com/schedules/$date/"), date)
    }

    suspend fun fetchDetail(url: String, home: String, away: String): Detail = withContext(Dispatchers.IO) {
        parseDetail(get(url), home, away)
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-GB,en;q=0.9")
            .build()
        return http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 403 || body.contains("<title>Just a moment", ignoreCase = true)) {
                throw BlockedException("LiveSoccerTV asked for a browser check")
            }
            if (!resp.isSuccessful) throw IOException("LiveSoccerTV answered ${resp.code}")
            body
        }
    }

    /**
     * The "International Coverage" table: rows of country name + channel links. Rows that also link
     * to other matches (related fixtures) are skipped.
     */
    fun parseDetail(html: String, home: String, away: String): Detail {
        val doc = Jsoup.parse(html, "https://www.livesoccertv.com/")
        val coverage = LinkedHashSet<Pair<String, String>>()
        for (tr in doc.select("tr")) {
            if (tr.selectFirst("a[href*=/match/]") != null) continue
            val links = tr.select("a[href*=/channels/]")
            if (links.isEmpty()) continue
            val firstCell = tr.selectFirst("td, th")
            val country = firstCell?.takeIf { it.selectFirst("a[href*=/channels/]") == null }?.text()?.let(::clean).orEmpty()
            for (a in links) {
                val name = channelName(a.attr("title").ifBlank { a.text() })
                if (name.isNotEmpty()) coverage += country to name
            }
        }
        val logos = doc.select("img")
            .map { it.absUrl("src").ifBlank { it.absUrl("data-src") } to it.attr("alt") }
            .filter { (src, _) -> src.contains("/teams/") && src.contains("/logos/") }
            .distinctBy { it.first }
        fun file(src: String) = src.substringAfterLast('/').substringBeforeLast('.')
        fun logoFor(team: String, fallback: Int): String? {
            val slug = slug(team)
            return logos.firstOrNull { (src, alt) -> file(src) == slug || alt.equals(team, ignoreCase = true) }?.first
                ?: logos.firstOrNull { (src, _) -> slug.isNotEmpty() && file(src).startsWith(slug.substringBefore('-')) }?.first
                ?: logos.getOrNull(fallback)?.first
        }
        return Detail(coverage.toList(), logoFor(home, 0), logoFor(away, 1))
    }

    private fun slug(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
            .lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

    fun parse(html: String, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Match> {
        val doc = Jsoup.parse(html, "https://www.livesoccertv.com/")
        val out = ArrayList<Match>()
        var competition: String? = null
        var league: String? = null
        for (tr in doc.select("tr")) {
            val matchLink = tr.selectFirst("a[href*=/match/]")
            val compLink = tr.selectFirst("a[href*=/competitions/]")
            if (compLink != null && matchLink == null) {
                competition = clean(compLink.text())
                league = compLink.attr("href").substringAfter("/competitions/", "").trim('/').ifBlank { null }
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
            val rowLogos = tr.select("img").map { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
                .filter { it.contains("/teams/") && it.contains("/logos/") }.distinct()
            out += Match(
                id = md5("$date|$home|$away").take(12),
                kickoff = kickoff(tr, date, zone),
                competition = competition,
                home = home,
                away = away,
                broadcasters = channels.toList(),
                url = matchLink.absUrl("href").substringBefore('#').ifBlank { null },
                league = league,
                homeLogo = rowLogos.getOrNull(0),
                awayLogo = rowLogos.getOrNull(1),
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
