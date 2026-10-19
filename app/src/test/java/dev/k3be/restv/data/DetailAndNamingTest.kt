package dev.k3be.restv.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailAndNamingTest {
    private val html = """
        <img src="https://cdn.livesoccertv.com/tt/images/teams/england/logos/liverpool.png" alt="Liverpool">
        <img src="https://cdn.livesoccertv.com/tt/images/teams/spain/logos/villarreal.png" alt="Villarreal">
        <h2>International Coverage</h2>
        <table>
          <tr><td>Australia</td><td><a href="/channels/stan-sport/">Stan Sport</a></td></tr>
          <tr><td>Great Britain</td><td><a href="/channels/sky-sports-main-event/">Sky Sports Main Event</a>, <a href="/channels/x/">TNT Sports 1</a></td></tr>
        </table>
        <table><tr><td><a href="/match/other/">PSG vs Barcelona</a></td><td><a href="/channels/y/">Canal+</a></td></tr></table>
        <img src="https://cdn.livesoccertv.com/tt/images/teams/france/logos/psg.png">
    """.trimIndent()

    @Test fun parsesWorldwideCoverageAndCrests() {
        val d = LiveSoccerTv(OkHttpClient()).parseDetail(html, "Liverpool", "Villarreal")
        assertEquals(
            listOf("Australia" to "Stan Sport", "Great Britain" to "Sky Sports Main Event", "Great Britain" to "TNT Sports 1"),
            d.coverage,
        )
        assertTrue(d.homeLogo!!.endsWith("liverpool.png"))
        assertTrue(d.awayLogo!!.endsWith("villarreal.png"))
    }

    @Test fun footballIconReadsAsO() {
        assertEquals("beIN SPORTS 1", Naming.cleanChannelName("AR: beIN SP⚽RTS 1 HD"))
        assertEquals("beIN SPORTS 1", Naming.cleanChannelName("AR| beIN SP⚽️RTS 1 ᴴᴰ"))
    }

    @Test fun separators() {
        assertTrue(Naming.isSeparator("##### BEIN SPORTS #####"))
        assertTrue(!Naming.isSeparator("UK: Sky Sports Main Event 4K"))
    }
}
