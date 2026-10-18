package dev.k3be.restv.data.xtream

import dev.k3be.restv.data.db.LoginEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class XtreamException(message: String) : IOException(message)

data class AuthInfo(
    val expiresAt: Long?,
    val maxConnections: Int,
    val activeConnections: Int,
    val timezone: String?,
    val formats: List<String>,
)

/** Talks to an Xtream Codes panel through player_api.php. */
class XtreamClient(private val http: OkHttpClient, private val json: Json) {

    companion object {
        /** Accepts "tv.example.com", "http://tv.example.com:8080/" and similar. */
        fun normalizeHost(input: String): String {
            var h = input.trim().trimEnd('/')
            if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://$h"
            return h
        }
    }

    private fun apiUrl(login: LoginEntity, action: String?, extra: Map<String, String> = emptyMap()): HttpUrl {
        val base = "${normalizeHost(login.host)}/player_api.php".toHttpUrlOrNull()
            ?: throw XtreamException("That server address doesn't look right")
        return base.newBuilder().apply {
            addQueryParameter("username", login.username)
            addQueryParameter("password", login.password)
            if (action != null) addQueryParameter("action", action)
            extra.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    fun liveUrl(login: LoginEntity, streamId: Long, ext: String): String {
        val base = normalizeHost(login.host).toHttpUrlOrNull()
            ?: throw XtreamException("That server address doesn't look right")
        return base.newBuilder()
            .addPathSegment("live")
            .addPathSegment(login.username)
            .addPathSegment(login.password)
            .addPathSegment("$streamId.$ext")
            .build().toString()
    }

    suspend fun auth(login: LoginEntity): AuthInfo = withContext(Dispatchers.IO) {
        val body = getString(apiUrl(login, null))
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw XtreamException("The server didn't answer like an Xtream panel")
        val ui = root["user_info"] as? JsonObject ?: throw XtreamException("No account info from the server")
        if (ui.int("auth") != 1) throw XtreamException("Wrong username or password")
        val status = ui.str("status")
        if (status != null && !status.equals("Active", true)) throw XtreamException("Subscription status: $status")
        val si = root["server_info"] as? JsonObject
        AuthInfo(
            expiresAt = ui.long("exp_date")?.let { it * 1000 },
            maxConnections = ui.int("max_connections") ?: 1,
            activeConnections = ui.int("active_cons") ?: 0,
            timezone = si?.str("timezone"),
            formats = (ui["allowed_output_formats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
        )
    }

    /** Downloads an action's JSON array to [target]. Big lists are written to disk first, then parsed. */
    suspend fun download(login: LoginEntity, action: String, target: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiUrl(login, action)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw XtreamException("Server error ${resp.code} on $action")
            val body = resp.body ?: throw XtreamException("Empty answer on $action")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun readArray(file: File, onItem: (JsonObject) -> Unit) {
        file.inputStream().buffered().use { input ->
            json.decodeToSequence(input, JsonObject.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
                .forEach(onItem)
        }
    }

    suspend fun shortEpg(login: LoginEntity, streamId: Long, limit: Int = 2): JsonObject? = withContext(Dispatchers.IO) {
        val body = getString(apiUrl(login, "get_short_epg", mapOf("stream_id" to streamId.toString(), "limit" to limit.toString())))
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    private fun getString(url: HttpUrl): String {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw XtreamException("Server error ${resp.code}")
            return resp.body?.string() ?: throw XtreamException("Empty answer from the server")
        }
    }
}

// Xtream panels mix strings, numbers and nulls freely; these read any of them.
fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.takeIf { it.isNotBlank() && it != "null" }
}

fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()
fun JsonObject.int(key: String): Int? = long(key)?.toInt()
fun JsonObject.bool(key: String): Boolean = when (str(key)?.lowercase()) {
    "1", "true", "yes" -> true
    else -> false
}
