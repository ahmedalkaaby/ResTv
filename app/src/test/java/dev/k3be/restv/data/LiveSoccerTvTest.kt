package dev.k3be.restv.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LiveSoccerTvTest {
    private val html = """
        <table>
        <tr><td><a href="/competitions/england/premier-league/">Premier League</a></td></tr>
        <tr><td><span dv="1792429200000">19:00</span></td><td><a href="/match/1/a-vs-b/">Arsenal vs Chelsea</a></td>
            <td><a href="/channels/1/" title="beIN Sports Arabia 5 HD (live stream available)">x</a>
                <a href="/channels/2/">51 Kuwait (Algeria, Bahrain, Chad…)</a></td></tr>
        <tr><td>21:45</td><td><a href="/match/2/x/">Real Madrid v Barcelona</a></td><td><a href="/channels/3/">SSC 1</a></td></tr>
        <tr><td>LIVE</td><td><a href="/match/inter-vs-napoli/abc">Inter 1 - 0 Napoli</a></td><td><a href="/channels/4/">beIN Sports 3</a></td></tr>
        </table>
    """.trimIndent()

    @Test fun parsesMatches() {
        val list = LiveSoccerTv(OkHttpClient()).parse(html, LocalDate.of(2026, 10, 19), ZoneId.of("Asia/Baghdad"))
        assertEquals(3, list.size)
        assertEquals("Napoli", list.first { it.home == "Inter" }.away)
        assertEquals("Arsenal", list[0].home)
        assertEquals("Premier League", list[0].competition)
        assertEquals(listOf("beIN Sports Arabia 5 HD", "51 Kuwait"), list[0].broadcasters)
        assertEquals(1792429200000L, list[0].kickoff)
        assertEquals("Barcelona", list[1].away)
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 19, 21, 45, 0, 0, ZoneId.of("Asia/Baghdad")).toInstant().toEpochMilli(), list[1].kickoff)
    }
}
