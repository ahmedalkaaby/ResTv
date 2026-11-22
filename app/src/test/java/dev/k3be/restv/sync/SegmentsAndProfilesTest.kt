package dev.k3be.restv.sync

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.data.Segment
import dev.k3be.restv.data.Segments
import dev.k3be.restv.data.db.AccountEntity
import dev.k3be.restv.data.db.AppDatabase
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.data.db.ProfileEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SegmentsAndProfilesTest {
    @Test fun readsIntroRecapAndCredits() = runBlocking {
        val server = MockWebServer()
        var asked: okhttp3.HttpUrl? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked = request.requestUrl
                return MockResponse().setBody(
                    """{"tmdb_id":1396,"type":"tv","intro":[{"start_ms":null,"end_ms":14600},{"start_ms":0,"end_ms":99000}],
                       "recap":[],"credits":[{"start_ms":2700000,"end_ms":null}],"preview":[{"start_ms":0,"end_ms":0}]}"""
                )
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        val seg = Segments(app.http, app.json, server.url("/v4/media").toString())
        val got = seg.get("1396", 1, 2, 2_900_000)
        assertEquals(listOf(Segment("intro", 0, 14_600), Segment("credits", 2_700_000, null)), got)
        assertEquals("1396", asked!!.queryParameter("tmdb_id"))
        assertEquals("2", asked!!.queryParameter("episode"))
        // Cached: no second request.
        seg.get("1396", 1, 2, 2_900_000)
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test fun limitReachedMeansWaitNotError() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(429).setBody("""{"retry_after_seconds":600}""")
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        val seg = Segments(app.http, app.json, server.url("/v4/media").toString())
        assertEquals(emptyList<Segment>(), seg.get("1", null, null, null))
        assertEquals(emptyList<Segment>(), seg.get("2", null, null, null))
        assertEquals(1, server.requestCount) // paused after the 429
        server.shutdown()
    }

    @Test fun profilesKeepTheirOwnHistory() {
        val account = AccountEntity(7, "Ahmed", 0)
        val login = LoginEntity(id = 1, accountId = 7, host = "h", username = "u", password = "p")
        val main = Session(account, login, ProfileEntity(1, 7, "Ahmed", 0, main = true, createdAt = 0))
        val kid = Session(account, login, ProfileEntity(2, 7, "Kids", 0, main = false, createdAt = 0))
        assertEquals(7, main.viewer) // existing history stays with the main profile
        assertNotEquals(main.viewer, kid.viewer)
        assertEquals(-2, kid.viewer)
    }

    @Test fun upgradeKeepsData() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = MigrationTestHelper(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
        helper.createDatabase("mig", 2).apply {
            execSQL("INSERT INTO account (id, name, lastUsedAt) VALUES (1, 'Me', 0)")
            close()
        }
        helper.runMigrationsAndValidate("mig", 3, true)
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "mig").allowMainThreadQueries().build()
        runBlocking { assertEquals("Me", db.accounts().lastUsed()?.name) }
        db.close()
    }
}
