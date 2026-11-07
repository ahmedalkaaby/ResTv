package dev.k3be.restv.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneInputTest {
    @Test fun servesPageAndReceivesValues() {
        var got: Map<String, String>? = null
        val latch = CountDownLatch(1)
        val p = PhoneInput("SubDL key", listOf(PhoneInput.Field("v", "API key", "old")), { got = it; latch.countDown() }, hostOverride = "127.0.0.1")
        val url = p.start()!!
        val http = OkHttpClient()
        try {
            val page = http.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
            assertTrue(page.contains("API key") && page.contains("value=\"old\""))
            // Wrong path: refused.
            val wrong = url.substringBeforeLast('/') + "/nope"
            assertEquals(404, http.newCall(Request.Builder().url(wrong).build()).execute().use { it.code })
            // Arabic and symbols survive the trip.
            val body = FormBody.Builder().add("v", "abc123 مفتاح&=").build()
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { assertEquals(200, it.code) }
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals("abc123 مفتاح&=", got!!["v"])
        } finally { p.stop() }
    }

    @Test fun parsesWholePlaylistLink() {
        assertEquals(
            Triple("http://example.com:8080", "me", "p@ss"),
            PhoneInput.parseXtreamLink("http://example.com:8080/get.php?username=me&password=p%40ss&type=m3u_plus&output=ts"),
        )
        assertEquals(null, PhoneInput.parseXtreamLink("http://example.com:8080"))
    }
}
