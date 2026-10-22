package dev.k3be.restv.sync

import androidx.test.core.app.ApplicationProvider
import dev.k3be.restv.RestvApp
import dev.k3be.restv.data.Artwork
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtworkTest {
    @Test fun findsBackdropAndEnglishLogo() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return MockResponse().setBody(
                    when {
                        path.endsWith("/search/movie") -> """{"results":[{"id":438631,"title":"Dune"}]}"""
                        path.endsWith("/movie/438631/images") -> """{
                            "backdrops":[{"file_path":"/text.jpg","iso_639_1":"en","vote_average":9},{"file_path":"/clean.jpg","iso_639_1":null,"vote_average":5}],
                            "logos":[{"file_path":"/ar.png","iso_639_1":"ar","vote_average":9},{"file_path":"/en.png","iso_639_1":"en","vote_average":4}]}"""
                        else -> "{}"
                    }
                )
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        app.prefs.setTmdbKey("KEY")
        val dir = File(app.filesDir, "art-test").apply { deleteRecursively(); mkdirs() }
        val art = Artwork(app.http, app.json, dir, app.prefs, app.vod, server.url("/3").toString().trimEnd('/'))
        val a = art.get("movie", 1, "Dune", 2021)
        assertEquals("https://image.tmdb.org/t/p/w780/clean.jpg", a.backdrop) // no text on the picture
        assertEquals("https://image.tmdb.org/t/p/w500/en.png", a.logo) // English logo first
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("year") == "2021")
        // Second time comes from memory, no request.
        art.get("movie", 1, "Dune", 2021)
        assertEquals(2, server.requestCount)
        server.shutdown()
    }
}
