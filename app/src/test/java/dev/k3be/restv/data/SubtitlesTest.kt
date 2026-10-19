package dev.k3be.restv.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitlesTest {
    private val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\nمرحبا <i>بك</i>\r\n\r\n2\r\n00:00:04,000 --> 00:00:06,000\r\nسطر ثاني\r\nوثالث\r\n"

    @Test fun parsesSrtAndFindsCue() {
        val cues = SubDl.parse(srt)
        assertEquals(2, cues.size)
        assertEquals("مرحبا بك", cues[0].text)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("سطر ثاني\nوثالث", SubDl.at(cues, 5_000)?.text)
        assertEquals(null, SubDl.at(cues, 3_700))
    }

    @Test fun decodesWindows1256() {
        val bytes = srt.toByteArray(Charset.forName("windows-1256"))
        assertTrue(SubDl.decode(bytes).contains("مرحبا"))
        assertTrue(SubDl.decode(srt.toByteArray()).contains("مرحبا"))
    }

    @Test fun episodeNames() {
        assertTrue(SubDl.episodeIn("The.Bear.S02E05.1080p.srt", 2, 5))
        assertTrue(!SubDl.episodeIn("The.Bear.S02E06.1080p.srt", 2, 5))
        assertTrue(SubDl.episodeIn("bear 2x05.srt", 2, 5))
    }

    @Test fun searchesAndDownloadsSeasonPack() = runBlocking {
        val server = MockWebServer()
        server.start()
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Show.S01E01.srt")); z.write("1\n00:00:01,000 --> 00:00:02,000\nواحد\n".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("Show.S01E02.srt")); z.write("1\n00:00:01,000 --> 00:00:02,000\nاثنان\n".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        server.enqueue(MockResponse().setBody("""{"status":true,"results":[{"name":"Show","tmdb_id":1}],
            "subtitles":[{"release_name":"Show.S01.WEB","name":"x","url":"${server.url("/pack.zip")}","season":1,"episode":null,"full_season":true},
                         {"release_name":"Show.S02E02","url":"/other.zip","season":2,"episode":2,"full_season":false}]}"""))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(zip)))
        val sub = SubDl(OkHttpClient(), Json { ignoreUnknownKeys = true }, File("build/tmp/subs-test").apply { deleteRecursively(); mkdirs() }, server.url("").toString().trimEnd('/'))
        val q = SubQuery("Show", 2020, "1", true, 1, 2)
        val opts = sub.search("KEY", q)
        assertEquals(1, opts.size)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("tmdb_id=1") && req.path!!.contains("languages=AR") && req.path!!.contains("episode_number=2"))
        val cues = sub.cues(opts[0], q)
        assertEquals("اثنان", cues.single().text)
        server.shutdown()
    }
}
