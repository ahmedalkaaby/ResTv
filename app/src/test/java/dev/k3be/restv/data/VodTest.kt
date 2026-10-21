package dev.k3be.restv.data

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class VodTest {
    @Test fun parsesProviderNames() {
        assertEquals(VodNaming.Parsed("The Batman", 2022), VodNaming.parse("EN - The Batman (2022) [4K]"))
        assertEquals(VodNaming.Parsed("Dune: Part Two", 2024), VodNaming.parse("|EN| Dune: Part Two 2024 UHD"))
        assertEquals(VodNaming.Parsed("1917", 2019), VodNaming.parse("4K | EN - 1917 (2019)"))
        assertEquals(VodNaming.Parsed("Oppenheimer", 2023), VodNaming.parse("NF: Oppenheimer", "2023-07-21"))
        assertNull(VodNaming.parse("AR - الحشاشين").year)
    }

    @Test fun sourceTags() {
        assertEquals("AR SUBS", VodNaming.source("AR-SUBS - The Bear (2022)"))
        assertEquals("AR SUBS", VodNaming.source("|AR SUB| The Bear"))
        assertEquals("EN", VodNaming.source("EN - The Bear"))
        assertEquals("GR", VodNaming.source("GR: The Bear"))
        assertEquals("AR", VodNaming.source("The Bear", "AR | Netflix Series"))
        assertNull(VodNaming.source("The Bear"))
        assertEquals("The Bear", VodNaming.parse("AR-SUBS - The Bear (2022)").title)
        assertEquals(VodNaming.key(VodNaming.parse("AR-SUBS - The Bear").title), VodNaming.key(VodNaming.parse("EN - The Bear").title))
        assertEquals("Arabic subtitles", VodNaming.sourceDescription("AR SUBS"))
    }

    @Test fun versionLabels() {
        assertEquals("Arabic subtitles", dev.k3be.restv.ui.vod.versionTitle("AR-SUBS - Dune", null))
        assertEquals("Netflix Movies", dev.k3be.restv.ui.vod.versionTitle("Dune", "Netflix Movies"))
        assertEquals("English", dev.k3be.restv.ui.vod.versionTitle("Dune", "EN | Netflix Movies"))
        assertEquals("Standard", dev.k3be.restv.ui.vod.versionTitle("Dune", null))
        assertEquals("EN  ·  4K  ·  MKV  ·  Netflix Movies", dev.k3be.restv.ui.vod.versionDetail("Dune", 3, "mkv", "EN | Netflix Movies"))
    }

    @Test fun keysMatchAcrossSources() {
        assertEquals(VodNaming.key("The Godfather"), VodNaming.key("Godfather"))
        assertEquals(VodNaming.key("Amélie"), VodNaming.key("Amelie"))
        assertEquals(VodNaming.key("Fast & Furious"), VodNaming.key("Fast and Furious"))
        assertEquals(VodNaming.key("Spider-Man: No Way Home"), VodNaming.key("Spider Man No Way Home"))
    }

    @Test fun platforms() {
        assertEquals("Netflix", VodNaming.platformOf("EN| NETFLIX SERIES"))
        assertEquals("Apple TV+", VodNaming.platformOf("APPLE TV+ MOVIES"))
        assertEquals("OSN+", VodNaming.platformOf("AR | OSN+ Series"))
        assertEquals("HBO", VodNaming.platformOf("HBO MAX"))
        assertNull(VodNaming.platformOf("Hosni Mubarak docs"))
    }

    @Test fun parsesImdbNextData() {
        val data = """{"props":{"pageProps":{"pageData":{"chartTitles":{"edges":[
            {"currentRank":1,"node":{"id":"tt0111161","titleText":{"text":"The Shawshank Redemption"},"originalTitleText":{"text":"The Shawshank Redemption"},"releaseYear":{"year":1994},"ratingsSummary":{"aggregateRating":9.3},"primaryImage":{"url":"https://img/1.jpg"}}},
            {"currentRank":2,"node":{"id":"tt0068646","titleText":{"text":"The Godfather"},"releaseYear":{"year":1972},"ratingsSummary":{"aggregateRating":9.2}}}
        ]}}}}}"""
        val html = """<html><script id="__NEXT_DATA__" type="application/json">$data</script></html>"""
        val list = ImdbCharts(OkHttpClient(), Json { ignoreUnknownKeys = true }, File("build/tmp")).parse(html)
        assertEquals(2, list.size)
        assertEquals("The Shawshank Redemption", list[0].title)
        assertEquals(1994, list[0].year)
        assertEquals(9.3f, list[0].rating)
        assertEquals("tt0068646", list[1].id)
    }

    @Test fun parsesImdbJsonLd() {
        val html = """<script type="application/ld+json">{"@type":"ItemList","itemListElement":[{"@type":"ListItem","item":{"@type":"Movie","url":"https://www.imdb.com/title/tt0111161/","name":"The Shawshank Redemption","aggregateRating":{"ratingValue":9.3}}}]}</script>"""
        val list = ImdbCharts(OkHttpClient(), Json { ignoreUnknownKeys = true }, File("build/tmp")).parse(html)
        assertEquals("tt0111161", list.single().id)
    }
}
