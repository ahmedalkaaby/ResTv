package dev.k3be.restv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** One line of subtitles on screen. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** A subtitle file offered by SubDL. */
data class SubOption(val id: String, val release: String, val url: String, val hearingImpaired: Boolean)

/** What to look subtitles up by. [season]/[episode] only for series. */
data class SubQuery(
    val title: String,
    val year: Int?,
    val tmdbId: String?,
    val isSeries: Boolean,
    val season: Int = 0,
    val episode: Int = 0,
)

/**
 * Arabic subtitles from SubDL (free API key from subdl.com/panel/api). Files are downloaded once,
 * decoded (UTF-8, UTF-16 or Windows-1256, common for Arabic) and parsed into cues the player draws.
 */
class SubDl(
    private val http: OkHttpClient,
    private val json: Json,
    private val cacheDir: File,
    private val apiBase: String = "https://api.subdl.com",
) {

    suspend fun search(key: String, q: SubQuery, language: String = "AR"): List<SubOption> = withContext(Dispatchers.IO) {
        val url = "$apiBase/api/v1/subtitles".toHttpUrl().newBuilder().apply {
            addQueryParameter("api_key", key)
            addQueryParameter("languages", language)
            addQueryParameter("subs_per_page", "30")
            addQueryParameter("unpack", "1")
            addQueryParameter("type", if (q.isSeries) "tv" else "movie")
            if (!q.tmdbId.isNullOrBlank() && q.tmdbId != "0") addQueryParameter("tmdb_id", q.tmdbId)
            else {
                addQueryParameter("film_name", q.title)
                if (q.year != null && !q.isSeries) addQueryParameter("year", q.year.toString())
            }
            if (q.isSeries) {
                addQueryParameter("season_number", q.season.toString())
                addQueryParameter("episode_number", q.episode.toString())
            }
        }.build()
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IOException("SubDL rejected the API key")
            r.body?.string().orEmpty()
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw IOException("SubDL sent an unexpected answer")
        if ((root["status"] as? JsonPrimitive)?.content == "false") {
            val err = (root["error"] as? JsonPrimitive)?.content ?: "no subtitles"
            if (err.contains("api", ignoreCase = true) && err.contains("key", ignoreCase = true)) throw IOException("SubDL: $err")
            return@withContext emptyList()
        }
        val subs = root["subtitles"] as? JsonArray ?: return@withContext emptyList()
        val out = ArrayList<SubOption>()
        for (el in subs) {
            val s = el as? JsonObject ?: continue
            val release = s.s("release_name") ?: s.s("name") ?: "Subtitle"
            val hi = s.s("hi") == "true" || s.s("hi") == "1"
            val unpacked = (s["unpack_files"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                .filter { f -> f.s("format")?.lowercase().let { it == null || it in setOf("srt", "vtt", "ass", "ssa", "sub") } }
            val files = if (q.isSeries) unpacked.filter { f ->
                val se = f.s("season")?.toIntOrNull(); val ep = f.s("episode")?.toIntOrNull()
                (se == null || se == q.season) && (ep == null || ep == q.episode) ||
                    episodeIn(f.s("name").orEmpty(), q.season, q.episode)
            } else unpacked
            if (files.isNotEmpty()) {
                files.take(2).forEach { f ->
                    val u = absolute(f.s("url") ?: return@forEach)
                    out += SubOption(u, f.s("release_name") ?: f.s("name") ?: release, u, f.s("hi") == "true" || hi)
                }
            } else {
                // Not unpacked: the whole zip; for a season pack the right episode is picked from inside.
                if (q.isSeries) {
                    val se = s.s("season")?.toIntOrNull(); val ep = s.s("episode")?.toIntOrNull()
                    val fullSeason = s.s("full_season") == "true"
                    if (se != null && se != q.season) continue
                    if (!fullSeason && ep != null && ep != q.episode) continue
                }
                val u = absolute(s.s("url") ?: continue)
                out += SubOption(u, release, u, hi)
            }
        }
        // Not-for-hearing-impaired first: fewer [music] lines.
        out.distinctBy { it.url }.sortedBy { it.hearingImpaired }
    }

    /** Null when the key works; otherwise what's wrong, in plain words. */
    suspend fun check(key: String): String? = withContext(Dispatchers.IO) {
        val k = key.trim()
        if (k.isEmpty()) return@withContext "No key entered"
        val url = "$apiBase/api/v1/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", k).addQueryParameter("film_name", "Inception")
            .addQueryParameter("type", "movie").addQueryParameter("languages", "AR").build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val ok = (root?.get("status") as? JsonPrimitive)?.content == "true"
                when {
                    ok -> null
                    r.code == 401 || r.code == 403 -> "SubDL says this key isn't valid."
                    root != null -> "SubDL: " + ((root["error"] as? JsonPrimitive)?.content ?: "the key didn't work")
                    else -> "SubDL answered ${r.code}"
                }
            }
        } catch (e: Exception) {
            "Couldn't reach SubDL from this TV (${e.javaClass.simpleName})."
        }
    }

    /** Downloads (once) and parses a subtitle; [q] picks the episode out of a season pack. */
    suspend fun cues(option: SubOption, q: SubQuery): List<Cue> = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "subs").apply { mkdirs() }
        val file = File(dir, option.url.hashCode().toUInt().toString() + "_${q.season}_${q.episode}.txt")
        val text = if (file.exists()) file.readText() else {
            val bytes = http.newCall(Request.Builder().url(option.url).header("User-Agent", "Restv/1.0").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Subtitle download failed (${r.code})")
                r.body?.bytes() ?: ByteArray(0)
            }
            val raw = if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) fromZip(bytes, q) else bytes
            decode(raw).also { runCatching { file.writeText(it) } }
        }
        parse(text)
    }

    private fun fromZip(bytes: ByteArray, q: SubQuery): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.lowercase()
                if (!e.isDirectory && (name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".ass") || name.endsWith(".ssa"))) {
                    entries += e.name to zip.readBytes()
                }
            }
        }
        if (entries.isEmpty()) throw IOException("No subtitle file in the download")
        val pick = if (q.isSeries) entries.firstOrNull { episodeIn(it.first, q.season, q.episode) } else null
        return (pick ?: entries.firstOrNull { it.first.lowercase().endsWith(".srt") } ?: entries.first()).second
    }

    companion object {
        private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

        private fun absolute(u: String) = if (u.startsWith("http")) u else "https://dl.subdl.com" + (if (u.startsWith("/")) u else "/$u")

        /** "Show.S01E02.1080p.srt", "1x02", "E02" with the right season. */
        fun episodeIn(name: String, season: Int, episode: Int): Boolean {
            val n = name.lowercase()
            val m = Regex("""s(\d{1,2})[ ._-]*e(\d{1,3})""").find(n) ?: Regex("""(\d{1,2})x(\d{1,3})""").find(n)
            if (m != null) return m.groupValues[1].toInt() == season && m.groupValues[2].toInt() == episode
            val e = Regex("""(?:^|[^a-z0-9])e(?:p|pisode)?[ ._-]*(\d{1,3})(?:[^0-9]|$)""").find(n) ?: return false
            return e.groupValues[1].toInt() == episode
        }

        /** UTF-8 when valid, UTF-16 by BOM, otherwise Windows-1256 (most old Arabic subtitles). */
        fun decode(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).removePrefix("﻿")
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).removePrefix("﻿")
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1256"))
            }
        }

        private val time = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
        private val shortTime = Regex("""(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2})[,.](\d{1,3})""")
        private val tags = Regex("""<[^>]+>|\{[^\}]*\}""")

        private fun ms(h: String, m: String, s: String, f: String): Long =
            ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()

        /** SRT and WebVTT; ASS/SSA dialogue lines too. */
        fun parse(text: String): List<Cue> {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            if (lines.any { it.startsWith("Dialogue:") }) return parseAss(lines)
            val out = ArrayList<Cue>()
            var i = 0
            while (i < lines.size) {
                val l = lines[i]
                val m = time.find(l)
                val sm = if (m == null) shortTime.find(l) else null
                if (m == null && sm == null) { i++; continue }
                val (start, end) = if (m != null) {
                    val g = m.groupValues
                    ms(g[1], g[2], g[3], g[4]) to ms(g[5], g[6], g[7], g[8])
                } else {
                    val g = sm!!.groupValues
                    ms("0", g[1], g[2], g[3]) to ms("0", g[4], g[5], g[6])
                }
                i++
                val body = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i].trim())
                    i++
                }
                val t = tags.replace(body.toString(), "").trim()
                if (t.isNotEmpty() && end > start) out += Cue(start, end, t)
            }
            return out.sortedBy { it.startMs }
        }

        private fun parseAss(lines: List<String>): List<Cue> {
            val t = Regex("""(\d+):(\d{2}):(\d{2})[.](\d{1,2})""")
            return lines.filter { it.startsWith("Dialogue:") }.mapNotNull { l ->
                val parts = l.removePrefix("Dialogue:").split(',', limit = 10)
                if (parts.size < 10) return@mapNotNull null
                val a = t.find(parts[1]) ?: return@mapNotNull null
                val b = t.find(parts[2]) ?: return@mapNotNull null
                val text = tags.replace(parts[9].replace("\\N", "\n").replace("\\n", "\n"), "").trim()
                if (text.isEmpty()) null else Cue(
                    ms(a.groupValues[1], a.groupValues[2], a.groupValues[3], a.groupValues[4] + "0"),
                    ms(b.groupValues[1], b.groupValues[2], b.groupValues[3], b.groupValues[4] + "0"),
                    text,
                )
            }.sortedBy { it.startMs }
        }

        /** The cue showing at [ms], by binary search. */
        fun at(cues: List<Cue>, ms: Long): Cue? {
            var lo = 0
            var hi = cues.lastIndex
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (cues[mid].startMs <= ms) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            // Overlapping cues: look back a little for one still on screen.
            for (k in found downTo maxOf(0, found - 3)) if (k >= 0 && cues[k].endMs > ms) return cues[k]
            return null
        }
    }
}
