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

    @Test fun detailsCastCrewAndFacts() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return MockResponse().setBody(
                    when {
                        url.encodedPath.endsWith("/movie/157336") && url.queryParameter("append_to_response")!!.contains("credits") -> """{
                            "id":157336,"title":"Interstellar","tagline":"Mankind was born on Earth.","runtime":169,
                            "release_date":"2014-11-05","vote_average":8.4,"vote_count":36000,"original_language":"en",
                            "genres":[{"name":"Adventure"},{"name":"Drama"}],
                            "production_companies":[{"name":"Legendary"},{"name":"Syncopy"}],
                            "credits":{"cast":[{"name":"Matthew McConaughey","character":"Cooper","profile_path":"/mm.jpg"},{"name":"Anne Hathaway","character":"Brand","profile_path":null}],
                                       "crew":[{"name":"Christopher Nolan","job":"Director","department":"Directing"},{"name":"Jonathan Nolan","job":"Writer","department":"Writing"}]},
                            "release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG-13"}]}]},
                            "recommendations":{"results":[{"title":"Inception","release_date":"2010-07-15"},{"title":"The Martian","release_date":"2015-09-30"}]}}"""
                        else -> "{}"
                    }
                )
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        app.prefs.setTmdbKey("KEY")
        val dir = File(app.filesDir, "art-test2").apply { deleteRecursively(); mkdirs() }
        val art = Artwork(app.http, app.json, dir, app.prefs, app.vod, server.url("/3").toString().trimEnd('/'))
        val d = art.details("movie", "Interstellar", 2014, "157336")!!
        assertEquals(listOf("Christopher Nolan"), d.directors)
        assertEquals(listOf("Jonathan Nolan"), d.writers)
        assertEquals("Cooper", d.cast.first().role)
        assertEquals("https://image.tmdb.org/t/p/w185/mm.jpg", d.cast.first().photo)
        assertEquals(null, d.cast[1].photo)
        assertEquals("PG-13", d.certification)
        assertEquals(169, d.runtimeMin)
        assertEquals(listOf("Adventure", "Drama"), d.genres)
        assertEquals("Inception" to 2010, d.similar.first())
        assertEquals("English", d.language)
        // The facts panel reads them in order.
        val facts = dev.k3be.restv.ui.vod.detailFacts(d, "movie", null, null, null, null, 2014).toMap()
        assertEquals("2 h 49 min", facts["Runtime"])
        assertEquals("8.4 / 10 · 36,000 votes", facts["Score"])
        server.shutdown()
    }

    @Test fun trailersSeasonPostersAndEpisodes() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return MockResponse().setBody(when {
                    path.endsWith("/tv/108978") -> """{"id":108978,"name":"Reacher","overview":"Big man.",
                        "seasons":[{"season_number":0,"poster_path":null},{"season_number":1,"poster_path":"/s1.jpg"}],
                        "videos":{"results":[
                          {"site":"YouTube","key":"clip1","type":"Clip","name":"A clip"},
                          {"site":"Vimeo","key":"v","type":"Trailer","name":"Elsewhere"},
                          {"site":"YouTube","key":"tr1","type":"Trailer","name":"Official Trailer","official":true}]}}"""
                    path.endsWith("/tv/108978/season/1") -> """{"episodes":[
                        {"episode_number":1,"name":"Welcome to Margrave","overview":"Arrested.","still_path":"/e1.jpg","vote_average":8.1,"air_date":"2022-02-04","runtime":49},
                        {"episode_number":2,"name":"First Dance","vote_average":0}]}"""
                    else -> "{}"
                })
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        app.prefs.setTmdbKey("KEY")
        val art = Artwork(app.http, app.json, File(app.filesDir, "art-test3").apply { mkdirs() }, app.prefs, app.vod, server.url("/3").toString().trimEnd('/'))
        val d = art.details("series", "Reacher", 2022, "108978")!!
        assertEquals("Big man.", d.overview)
        assertEquals(listOf("tr1", "clip1"), d.videos.map { it.key })
        assertEquals(mapOf(1 to "https://image.tmdb.org/t/p/w342/s1.jpg"), d.seasonPosters)
        val eps = art.season("108978", 1)
        assertEquals("Welcome to Margrave", eps[0].name)
        assertEquals(8.1, eps[0].score!!, 0.001)
        assertEquals("https://image.tmdb.org/t/p/w300/e1.jpg", eps[0].still)
        assertEquals(null, eps[1].score)
        assertEquals("Feb 4, 2022", dev.k3be.restv.ui.vod.prettyAirDate("2022-02-04"))
        server.shutdown()
    }

    @Test fun keyCheckSaysWhatIsWrong() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val good = request.requestUrl!!.queryParameter("api_key") == "GOOD"
                return if (good) MockResponse().setBody("{}") else MockResponse().setResponseCode(401).setBody("{}")
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        val art = Artwork(app.http, app.json, File(app.filesDir, "a2"), app.prefs, app.vod, server.url("/3").toString().trimEnd('/'))
        assertEquals(null, art.check("GOOD"))
        assertTrue(art.check("BAD")!!.contains("isn't valid"))
        server.shutdown()
        // Nothing listening: reported as unreachable, not as a bad key.
        assertTrue(art.check("GOOD")!!.startsWith("Couldn't reach TMDB"))
    }

    @Test fun retriesWithoutYear() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val u = request.requestUrl!!
                return MockResponse().setBody(when {
                    u.encodedPath.endsWith("/search/tv") && u.queryParameter("first_air_date_year") != null -> """{"results":[]}"""
                    u.encodedPath.endsWith("/search/tv") -> """{"results":[{"id":1399}]}"""
                    u.encodedPath.endsWith("/tv/1399/images") -> """{"backdrops":[],"logos":[{"file_path":"/got.png","iso_639_1":"en"}]}"""
                    else -> "{}"
                })
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        app.prefs.setTmdbKey("KEY")
        val art = Artwork(app.http, app.json, File(app.filesDir, "a3").apply { deleteRecursively(); mkdirs() }, app.prefs, app.vod, server.url("/3").toString().trimEnd('/'))
        assertEquals("https://image.tmdb.org/t/p/w500/got.png", art.get("series", 5, "Game of Thrones", 2012).logo)
        server.shutdown()
    }
}
