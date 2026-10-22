package dev.k3be.restv.sync

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.SyncState
import dev.k3be.restv.data.db.AccountEntity
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.ui.vod.SeriesDetailScreen
import dev.k3be.restv.ui.vod.VodHomeScreen
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xlarge-land-television")
class VodScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun movies(n: Int) = (1..n).joinToString(",", "[", "]") { i ->
        """{"num":$i,"name":"EN - Movie $i (20${10 + i % 15})","stream_id":$i,"stream_icon":"","rating":"7.${i % 10}","added":"${1700000000 + i}","category_id":"${i % 40}","container_extension":"mkv"}"""
    }
    private fun series(n: Int) = (1..n).joinToString(",", "[", "]") { i ->
        """{"name":"Show $i","series_id":$i,"cover":"","plot":"p","genre":"Drama","releaseDate":"2020-01-01","last_modified":"${1700000000 + i}","rating":"8","backdrop_path":[],"category_id":"${i % 20}"}"""
    }
    private fun cats(n: Int) = (0 until n).joinToString(",", "[", "]") { """{"category_id":"$it","category_name":"${if (it == 1) "NETFLIX" else "Cat $it"}"}""" }
    private val seriesInfo = """{"seasons":[{"season_number":1,"name":"Season 1"}],"info":{"name":"Show 1","plot":"x","backdrop_path":[]},
        "episodes":{"1":[{"id":"101","episode_num":1,"title":"Show 1 - S01E01 - Pilot","container_extension":"mkv","season":1,"info":{"duration_secs":2400}},
        {"id":"102","episode_num":"2","title":"Ep two","container_extension":"mkv","season":"1","info":{}}]}}"""

    private fun start(): Pair<RestvApp, Session> {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                when (request.requestUrl?.queryParameter("action")) {
                    "get_vod_categories" -> cats(40)
                    "get_vod_streams" -> movies(5000)
                    "get_series_categories" -> cats(20)
                    "get_series" -> series(800)
                    "get_series_info" -> seriesInfo
                    else -> "[]"
                }
            )
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        val login = LoginEntity(id = 1, accountId = 1, host = server.url("/").toString(), username = "u", password = "p")
        runBlocking { app.sync.syncVod(login) { _, _ -> } }
        return app to Session(AccountEntity(1, "Me", 0), login)
    }

    @Test fun moviesHomeRenders() {
        val (_, session) = start()
        rule.setContent { VodHomeScreen(session, "movie", SyncState.Idle, {}, {}, {}, {}) }
        rule.waitForIdle()
        Thread.sleep(3000)
        rule.mainClock.advanceTimeBy(5000)
        rule.waitForIdle()
    }

    @Test fun seriesHomeRenders() {
        val (_, session) = start()
        rule.setContent { VodHomeScreen(session, "series", SyncState.Idle, {}, {}, {}, {}) }
        rule.waitForIdle()
        Thread.sleep(3000)
        rule.mainClock.advanceTimeBy(5000)
        rule.waitForIdle()
    }

    @Test fun homeRenders() {
        val (_, session) = start()
        rule.setContent { dev.k3be.restv.ui.home.HomeScreen(session, {}, { _, _ -> }, { _, _ -> }, {}) }
        rule.waitForIdle()
        Thread.sleep(3000)
        rule.mainClock.advanceTimeBy(5000)
        rule.waitForIdle()
    }

    @Test fun movieDetailRenders() {
        val (_, session) = start()
        rule.setContent { dev.k3be.restv.ui.vod.MovieDetailScreen(session, 1) { _, _, _ -> } }
        rule.waitForIdle()
        Thread.sleep(2000)
        rule.mainClock.advanceTimeBy(3000)
        rule.waitForIdle()
    }

    @Test fun settingsRenders() {
        val (_, session) = start()
        rule.setContent { dev.k3be.restv.ui.settings.SettingsScreen(session, SyncState.Idle, {}, {}) }
        rule.waitForIdle()
    }

    @Test fun platformRenders() {
        val (_, session) = start()
        rule.setContent { dev.k3be.restv.ui.vod.PlatformScreen(session, "Netflix") {} }
        rule.waitForIdle()
        Thread.sleep(2000)
        rule.mainClock.advanceTimeBy(3000)
        rule.waitForIdle()
    }

    @Test fun splashRenders() {
        rule.setContent { dev.k3be.restv.ui.Splash() }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
    }

    @Test fun seriesDetailRenders() {
        val (_, session) = start()
        rule.setContent { SeriesDetailScreen(session, 1) { _, _, _ -> } }
        rule.waitForIdle()
        Thread.sleep(2000)
        rule.mainClock.advanceTimeBy(3000)
        rule.waitForIdle()
    }
}
