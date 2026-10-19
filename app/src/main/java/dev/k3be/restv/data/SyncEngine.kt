package dev.k3be.restv.data

import androidx.room.withTransaction
import dev.k3be.restv.data.db.AppDatabase
import dev.k3be.restv.data.db.ChannelEntity
import dev.k3be.restv.data.db.LiveCategoryEntity
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.data.db.MovieEntity
import dev.k3be.restv.data.db.SeriesEntity
import dev.k3be.restv.data.db.VodCategoryEntity
import dev.k3be.restv.data.xtream.XtreamClient
import dev.k3be.restv.data.xtream.bool
import dev.k3be.restv.data.xtream.int
import dev.k3be.restv.data.xtream.long
import dev.k3be.restv.data.xtream.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat

/** Pulls the provider's catalogue into the local database. Screens only ever read the database. */
class SyncEngine(
    private val db: AppDatabase,
    private val xtream: XtreamClient,
    private val cacheDir: File,
) {

    suspend fun syncLive(login: LoginEntity, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        onProgress("Checking your subscription")
        val info = xtream.auth(login)

        val dir = File(cacheDir, "sync").apply { mkdirs() }
        val catFile = File(dir, "live_categories.json")
        val chFile = File(dir, "live_streams.json")

        onProgress("Downloading categories")
        xtream.download(login, "get_live_categories", catFile)
        onProgress("Downloading channels")
        xtream.download(login, "get_live_streams", chFile)

        val categories = ArrayList<LiveCategoryEntity>()
        xtream.readArray(catFile) { o ->
            val id = o.str("category_id") ?: return@readArray
            val raw = o.str("category_name") ?: id
            val split = Naming.split(raw)
            categories += LiveCategoryEntity(
                loginId = login.id,
                categoryId = id,
                rawName = raw,
                name = split.name,
                groupKey = split.code,
                groupLabel = Naming.groupLabel(split.code),
                providerOrder = categories.size,
            )
        }

        val fmt = NumberFormat.getIntegerInstance()
        onProgress("Reading channels")
        val channels = ArrayList<ChannelEntity>(20_000)
        xtream.readArray(chFile) { o ->
            val streamId = o.long("stream_id") ?: return@readArray
            val raw = o.str("name") ?: return@readArray
            channels += ChannelEntity(
                loginId = login.id,
                streamId = streamId,
                num = o.int("num") ?: 0,
                rawName = raw,
                name = Naming.cleanChannelName(raw),
                categoryId = o.str("category_id") ?: "",
                icon = o.str("stream_icon"),
                epgChannelId = o.str("epg_channel_id"),
                hasArchive = o.bool("tv_archive"),
                quality = Naming.quality(raw),
                variantKey = Naming.variantKey(raw),
            )
        }

        db.withTransaction {
            db.live().clearCategories(login.id)
            db.live().clearChannels(login.id)
            db.live().insertCategories(categories)
            var saved = 0
            channels.chunked(1000).forEach { chunk ->
                db.live().insertChannels(chunk)
                saved += chunk.size
                onProgress("Saving channels · ${fmt.format(saved)} of ${fmt.format(channels.size)}")
            }
        }
        onProgress("Saved ${fmt.format(channels.size)} channels")

        db.logins().update(
            login.copy(
                expiresAt = info.expiresAt,
                maxConnections = info.maxConnections,
                serverTimezone = info.timezone,
                lastSyncAt = System.currentTimeMillis(),
            )
        )
        catFile.delete()
        chFile.delete()
    }

    /**
     * Movies and series, the kind the user is looking at first. Rows are saved in short transactions
     * so screens stay responsive, then titles the provider removed are deleted.
     */
    suspend fun syncVod(login: LoginEntity, first: String = "movie", onProgress: (String, Float?) -> Unit) = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "sync").apply { mkdirs() }
        val fmt = NumberFormat.getIntegerInstance()
        val kinds = if (first == "series") listOf("series", "movie") else listOf("movie", "series")
        for (kind in kinds) {
            val label = if (kind == "movie") "movies" else "series"
            val catFile = File(dir, "${kind}_categories.json")
            val listFile = File(dir, "${kind}_list.json")
            onProgress("Downloading your $label", null)
            xtream.download(login, if (kind == "movie") "get_vod_categories" else "get_series_categories", catFile)
            xtream.download(login, if (kind == "movie") "get_vod_streams" else "get_series", listFile) { f ->
                onProgress("Downloading your $label", f?.let { it * 0.5f })
            }

            val cats = ArrayList<VodCategoryEntity>()
            xtream.readArray(catFile) { o ->
                val id = o.str("category_id") ?: return@readArray
                val raw = o.str("category_name") ?: id
                cats += VodCategoryEntity(login.id, kind, id, raw, Naming.split(raw).name, cats.size, VodNaming.platformOf(raw))
            }

            val seen = HashSet<Long>(50_000)
            var saved = 0
            val movies = ArrayList<MovieEntity>(1000)
            val shows = ArrayList<SeriesEntity>(1000)
            var fraction = 0.5f
            fun flush() {
                if (movies.isNotEmpty()) { db.runInTransaction { db.vod().insertMovies(movies) }; saved += movies.size; movies.clear() }
                if (shows.isNotEmpty()) { db.runInTransaction { db.vod().insertSeries(shows) }; saved += shows.size; shows.clear() }
                onProgress("Saving your $label · ${fmt.format(saved)}", fraction)
            }
            xtream.readArray(listFile, onProgress = { f -> fraction = 0.5f + f * 0.5f }) { o ->
                if (kind == "movie") {
                    val id = o.long("stream_id") ?: return@readArray
                    val raw = o.str("name") ?: return@readArray
                    val p = VodNaming.parse(raw, o.str("year") ?: o.str("releasedate") ?: o.str("release_date"))
                    seen += id
                    movies += MovieEntity(
                        loginId = login.id, streamId = id, rawName = raw, name = p.title, year = p.year,
                        categoryId = o.str("category_id") ?: "", poster = o.str("stream_icon"),
                        rating = o.str("rating")?.toFloatOrNull()?.takeIf { it > 0 },
                        added = (o.long("added") ?: 0L) * 1000, ext = o.str("container_extension") ?: "mp4",
                        titleKey = VodNaming.key(p.title), quality = Naming.quality(raw),
                    )
                    if (movies.size == 1000) flush()
                } else {
                    val id = o.long("series_id") ?: return@readArray
                    val raw = o.str("name") ?: return@readArray
                    val p = VodNaming.parse(raw, o.str("releaseDate") ?: o.str("release_date") ?: o.str("year"))
                    val backdrop = (o["backdrop_path"] as? kotlinx.serialization.json.JsonArray)
                        ?.firstNotNullOfOrNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { u -> u.startsWith("http") } }
                    seen += id
                    shows += SeriesEntity(
                        loginId = login.id, seriesId = id, rawName = raw, name = p.title, year = p.year,
                        categoryId = o.str("category_id") ?: "", poster = o.str("cover"), backdrop = backdrop,
                        plot = o.str("plot"), genre = o.str("genre"),
                        rating = o.str("rating")?.toFloatOrNull()?.takeIf { it > 0 },
                        updated = (o.long("last_modified") ?: 0L) * 1000, titleKey = VodNaming.key(p.title),
                    )
                    if (shows.size == 1000) flush()
                }
            }
            flush()
            // Titles the provider no longer has.
            val existing = if (kind == "movie") db.vod().movieIds(login.id) else db.vod().seriesIds(login.id)
            val stale = existing.filter { it !in seen }
            stale.chunked(500).forEach { chunk ->
                db.runInTransaction { if (kind == "movie") db.vod().deleteMovies(login.id, chunk) else db.vod().deleteSeries(login.id, chunk) }
            }
            db.runInTransaction {
                db.vod().clearCategories(login.id, kind)
                db.vod().insertCategories(cats)
            }
            onProgress("Saved ${fmt.format(saved)} $label", 1f)
            catFile.delete()
            listFile.delete()
        }
    }
}
