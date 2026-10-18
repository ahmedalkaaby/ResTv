package dev.k3be.restv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.k3be.restv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Checks GitHub Releases for a newer APK, downloads it and hands it to the system installer. */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    data class Release(val versionCode: Int, val name: String, val apkUrl: String, val sizeBytes: Long)

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** Returns the newest release if it is newer than this build, else null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throw IOException("GitHub answered ${resp.code}")
            resp.body?.string() ?: throw IOException("Empty answer from GitHub")
        }
        val o = json.parseToJsonElement(body).jsonObject
        val tag = (o["tag_name"] as? JsonPrimitive)?.content ?: return@withContext null
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
        val asset = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["name"] as? JsonPrimitive)?.content?.endsWith(".apk") == true }
            ?: return@withContext null
        val url = (asset["browser_download_url"] as? JsonPrimitive)?.content ?: return@withContext null
        val size = (asset["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        if (code <= currentVersionCode) null else Release(code, tag, url, size)
    }

    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "restv-${release.versionCode}.apk")
        val req = Request.Builder().url(release.apkUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
        }
        file
    }

    /** True when the TV still has to allow Restv to install apps; opens that setting. */
    fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
