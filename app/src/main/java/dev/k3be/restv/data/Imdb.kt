package dev.k3be.restv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException

/**
 * IMDb charts, fetched by the TV (home connection) and cached for a day:
 * top = Top 250 movies, toptv = Top 250 shows, moviemeter / tvmeter = most popular right now.
 */
class ImdbCharts(private val http: OkHttpClient, private val json: Json, private val cacheDir: File) {

    data class Title(val id: String, val title: String, val originalTitle: String?, val year: Int?, val image: String?, val rating: Float?)

    enum class Chart(val path: String) { TOP("top"), TOP_TV("toptv"), POPULAR("moviemeter"), POPULAR_TV("tvmeter") }

    suspend fun chart(chart: Chart): List<Title> = withContext(Dispatchers.IO) {
        val f = File(cacheDir, "imdb/${chart.path}.json")
        val cached = runCatching { if (f.exists()) read(f.readText()) else null }.getOrNull()
        if (cached != null && cached.isNotEmpty() && System.currentTimeMillis() - f.lastModified() < 24 * 3_600_000L) return@withContext cached
        val fresh = runCatching { parse(download("https://www.imdb.com/chart/${chart.path}/")) }.getOrNull()
        if (!fresh.isNullOrEmpty()) {
            runCatching { f.parentFile?.mkdirs(); f.writeText(write(fresh)) }
            fresh
        } else cached ?: emptyList()
    }

    private fun download(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("IMDb answered ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    /** Reads the page's Next.js data; falls back to the JSON-LD list (no years there). */
    fun parse(html: String): List<Title> {
        val doc = Jsoup.parse(html)
        doc.selectFirst("script#__NEXT_DATA__")?.data()?.let { data ->
            val root = runCatching { json.parseToJsonElement(data) }.getOrNull()
            if (root != null) {
                val out = LinkedHashMap<String, Title>()
                collect(root, out)
                if (out.isNotEmpty()) return out.values.toList()
            }
        }
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { json.parseToJsonElement(script.data()).jsonObject }.getOrNull() ?: continue
            val items = root["itemListElement"] as? JsonArray ?: continue
            return items.mapNotNull { el ->
                val item = (el as? JsonObject)?.get("item") as? JsonObject ?: return@mapNotNull null
                val name = (item["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val url = (item["url"] as? JsonPrimitive)?.content.orEmpty()
                val id = Regex("""tt\d+""").find(url)?.value ?: name
                Title(
                    id = id,
                    title = (item["alternateName"] as? JsonPrimitive)?.content ?: name,
                    originalTitle = name,
                    year = null,
                    image = (item["image"] as? JsonPrimitive)?.content,
                    rating = ((item["aggregateRating"] as? JsonObject)?.get("ratingValue") as? JsonPrimitive)?.content?.toFloatOrNull(),
                )
            }
        }
        return emptyList()
    }

    /** Depth-first, so titles come out in chart order. */
    private fun collect(el: JsonElement, out: MutableMap<String, Title>) {
        when (el) {
            is JsonObject -> {
                val id = (el["id"] as? JsonPrimitive)?.content
                val titleText = (el["titleText"] as? JsonObject)?.get("text") as? JsonPrimitive
                if (id != null && id.startsWith("tt") && titleText != null) {
                    if (id !in out) out[id] = Title(
                        id = id,
                        title = titleText.content,
                        originalTitle = ((el["originalTitleText"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content,
                        year = ((el["releaseYear"] as? JsonObject)?.get("year") as? JsonPrimitive)?.content?.toIntOrNull(),
                        image = ((el["primaryImage"] as? JsonObject)?.get("url") as? JsonPrimitive)?.content,
                        rating = ((el["ratingsSummary"] as? JsonObject)?.get("aggregateRating") as? JsonPrimitive)?.content?.toFloatOrNull(),
                    )
                    return
                }
                el.values.forEach { collect(it, out) }
            }
            is JsonArray -> el.forEach { collect(it, out) }
            else -> {}
        }
    }

    private fun write(list: List<Title>): String = buildJsonArray {
        list.forEach { t ->
            add(buildJsonObject {
                put("id", t.id); put("title", t.title)
                t.originalTitle?.let { put("o", it) }
                t.year?.let { put("y", it) }
                t.image?.let { put("img", it) }
                t.rating?.let { put("r", it) }
            })
        }
    }.toString()

    private fun read(text: String): List<Title> =
        (json.parseToJsonElement(text) as JsonArray).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content
            Title(s("id") ?: return@mapNotNull null, s("title") ?: return@mapNotNull null, s("o"), s("y")?.toIntOrNull(), s("img"), s("r")?.toFloatOrNull())
        }
}
