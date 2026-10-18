package dev.k3be.restv.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One commentary stream, e.g. "Anis Sport 2". [lowUrl] is its low-bitrate twin, used as a fallback. */
data class AudioSource(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val lowUrl: String?,
)

/**
 * Commentary playlists in "name line, url line" format. The Anis FM list ships with the app;
 * its token is kept in one setting so a token change never means re-entering 38 links.
 */
class AudioSources(private val context: Context, private val prefs: Prefs) {

    private val raw: List<Pair<String, String>> by lazy {
        val lines = context.assets.open("audio/anis_fm.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() }
        lines.chunked(2).mapNotNull { if (it.size == 2 && it[1].startsWith("http")) it[0] to it[1].substringBefore('#') else null }
    }

    /** The token currently built into the bundled playlist. */
    val bundledToken: String by lazy {
        raw.firstNotNullOfOrNull { it.second.toHttpUrlOrNull()?.queryParameter("token") } ?: ""
    }

    fun list(): List<AudioSource> {
        val token = prefs.audioToken() ?: bundledToken
        val withToken = raw.map { (name, url) -> name to applyToken(url, token) }
        val byName = withToken.toMap()
        return withToken
            .filterNot { it.first.endsWith(" Low", ignoreCase = true) }
            .map { (name, url) ->
                AudioSource(
                    id = name,
                    name = name.removePrefix("Anis ").trim(),
                    group = groupOf(name),
                    url = url,
                    lowUrl = byName["$name Low"],
                )
            }
    }

    private fun applyToken(url: String, token: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        if (token.isBlank()) return url
        return u.newBuilder().setQueryParameter("token", token).build().toString()
    }

    private fun groupOf(name: String): String =
        name.removePrefix("Anis ").replace(Regex("""\s*\d+\s*$"""), "").trim().ifEmpty { "Other" }
}
