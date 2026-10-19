package dev.k3be.restv.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.k3be.restv.data.SyncEngine
import dev.k3be.restv.data.db.AppDatabase
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.data.xtream.XtreamClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Runs the real movie/series sync against a fake panel with a big catalogue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VodSyncTest {
    private fun movies(n: Int) = (1..n).joinToString(",", "[", "]") { i ->
        """{"num":$i,"name":"EN - Movie $i (20${10 + i % 15})","stream_type":"movie","stream_id":$i,"stream_icon":"http://x/$i.jpg","rating":"7.${i % 10}","added":"1700000000","category_id":"${i % 40}","container_extension":"mkv"}"""
    }
    private fun series(n: Int) = (1..n).joinToString(",", "[", "]") { i ->
        """{"num":$i,"name":"Show $i","series_id":$i,"cover":"http://x/s$i.jpg","plot":"p","genre":"Drama","releaseDate":"2020-01-01","last_modified":"1700000000","rating":"8","backdrop_path":["http://x/b$i.jpg"],"category_id":"${i % 20}"}"""
    }
    private fun cats(n: Int) = (0 until n).joinToString(",", "[", "]") { """{"category_id":"$it","category_name":"${if (it == 1) "NETFLIX" else "Cat $it"}","parent_id":0}""" }

    @Test fun syncsBigCatalogue() = runBlocking {
        val server = MockWebServer()
        val m = movies(60_000); val s = series(8_000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.requestUrl?.queryParameter("action")
                val body = when (action) {
                    "get_vod_categories" -> cats(40)
                    "get_vod_streams" -> m
                    "get_series_categories" -> cats(20)
                    "get_series" -> s
                    else -> "[]"
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val engine = SyncEngine(db, XtreamClient(OkHttpClient(), json), File(ctx.cacheDir, "t"))
        val login = LoginEntity(id = 1, accountId = 1, host = server.url("/").toString(), username = "u", password = "p")
        val messages = ArrayList<String>()
        engine.syncVod(login) { m, _ -> messages += m }
        assertEquals(60_000, db.vod().countMovies(1))
        assertEquals(8_000, db.vod().countSeries(1))
        println(messages.distinct().take(12))
        server.shutdown()
    }
}
