package dev.k3be.restv.data

import dev.k3be.restv.ui.vod.Rail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Small JSON copies of rows, written after each load and read first on the next launch. */
class Snapshots(private val dir: File, private val json: Json) {
    private val serializer = ListSerializer(Rail.serializer())

    suspend fun read(key: String): List<Rail>? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(serializer, File(dir, "$key.json").readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    suspend fun write(key: String, rails: List<Rail>) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.json.tmp")
            tmp.writeText(json.encodeToString(serializer, rails))
            tmp.renameTo(File(dir, "$key.json"))
        }
    }
}
