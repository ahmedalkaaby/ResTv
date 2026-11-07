package dev.k3be.restv.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.k3be.restv.data.Match
import dev.k3be.restv.data.MatchesRepo
import dev.k3be.restv.data.Naming
import dev.k3be.restv.data.Prefs
import dev.k3be.restv.data.db.AppDatabase
import dev.k3be.restv.data.db.ChannelEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MatchingTest {
    @Test fun findsRegionalServices() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val names = listOf(
            "AR: SHAHID SPORT 1 HD", "AR: SHAHID SPORT 2 HD", "AR: STARZPLAY SPORTS 1", "AR: THAMANYA 1",
            "AR| ALMANASA 1", "AR: beIN SP⚽RTS 1 HD", "FR: beIN SPORTS 1", "AU: beIN SPORTS 3",
            "AR: beIN SPORTS MAX 1", "UK: Sky Sports Main Event 4K", "##### SPORTS #####", "AR: MBC 1",
        )
        db.live().insertChannels(names.mapIndexed { i, raw ->
            ChannelEntity(1, i.toLong(), i, raw, Naming.cleanChannelName(raw), "c", null, null, false, Naming.quality(raw), Naming.variantKey(raw))
        })
        val repo = MatchesRepo(OkHttpClient(), Json, db, Prefs(ctx), ctx.filesDir)
        val m = Match("x", null, "Serie A", "Inter", "Napoli",
            listOf("Shahid VIP", "STARZPLAY", "Thmanyah", "Al Manassa", "beIN SPORTS 1", "beIN SPORTS 3"))
        val found = repo.coverage(1, m).channels.map { it.rawName }.toSet()
        assertEquals(
            setOf("AR: SHAHID SPORT 1 HD", "AR: SHAHID SPORT 2 HD", "AR: STARZPLAY SPORTS 1", "AR: THAMANYA 1",
                "AR| ALMANASA 1", "AR: beIN SP⚽RTS 1 HD"),
            found,
        )
    }

    @Test fun generalNamesDontPullWholeFamilies() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val names = (1..9).map { "AR: beIN SPORTS $it HD" } + listOf(
            "Movistar Accion", "Movistar Comedia", "Sky Family", "Sky Go Filme",
            "Sky Sports Main Event 4K", "SKY SPORTS MAIN EVENT FHD", "Sky Sports Main Event HD", "Sky Sports Main Event SD",
        )
        db.live().insertChannels(names.mapIndexed { i, raw ->
            ChannelEntity(1, i.toLong(), i, raw, Naming.cleanChannelName(raw), "c", null, null, false, Naming.quality(raw), Naming.variantKey(raw))
        })
        val repo = MatchesRepo(OkHttpClient(), Json, db, Prefs(ctx), ctx.filesDir)
        val m = Match("y", null, "Premier League", "A", "B", listOf("beIN SPORTS MENA", "Movistar+", "Sky Go", "Sky Sports Main Event"))
        val found = repo.coverage(1, m).channels.map { it.rawName }.toSet()
        // Only the three best copies of the channel actually named; no beIN 1–9, Movistar films or Sky Family.
        assertEquals(setOf("Sky Sports Main Event 4K", "SKY SPORTS MAIN EVENT FHD", "Sky Sports Main Event HD"), found)
    }

    private fun repoWith(vararg names: String): MatchesRepo {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking { db.live().insertChannels(names.mapIndexed { i, raw ->
            ChannelEntity(1, i.toLong(), i, raw, Naming.cleanChannelName(raw), "c", null, null, false, Naming.quality(raw), Naming.variantKey(raw))
        }) }
        return MatchesRepo(OkHttpClient(), Json, db, Prefs(ctx), ctx.filesDir)
    }

    private val europe = arrayOf(
        "ES: M+ LALIGA TV HD", "ES: M+ LALIGA TV 2", "ES: M+ LIGA DE CAMPEONES", "ES: M+ LIGA DE CAMPEONES 2", "ES: MOVISTAR ACCION",
        "ES: MOVISTAR COMEDIA", "ES: M+ DEPORTES", "ES: DAZN LALIGA", "ES: DAZN 1", "DE: DAZN 1", "DE: DAZN 2",
        "UK: Sky Sports Main Event", "UK: Sky Sports Premier League 4K", "UK: Sky Sports Football", "UK: Sky Sports Golf",
        "UK: Sky Cinema Family", "UK: Sky Sports F1", "UK: TNT Sports 1", "UK: TNT Sports 2",
        "CH: blue Sport 1", "CH: blue Sport 2", "CH: blue News", "SE: V Sport Fotboll", "SE: V Film Premiere",
        "US: CBS Sports Golazo", "US: Paramount Network", "US: ESPN", "US: ESPN 2", "US: ESPNews",
        "CA: TSN1", "CA: TSN 4", "CA: Sportsnet One", "FR: CANAL+ FOOT", "FR: CANAL+ CINEMA", "FR: beIN SPORTS 1",
        "AR: beIN SPORTS 1",
    )

    private suspend fun MatchesRepo.names(country: String, broadcaster: String, league: String?, comp: String?) =
        channelsFor(1, country, broadcaster, league, comp).map { it.rawName }.toSet()

    @Test fun networkNamesBecomeTheirSportsChannels() = runBlocking {
        val r = repoWith(*europe)
        // LaLiga on Movistar+: the LaLiga channels, never films; Champions League: Liga de Campeones.
        assertEquals(setOf("ES: M+ LALIGA TV HD", "ES: M+ LALIGA TV 2"), r.names("Spain", "Movistar+", "spain/primera-division", "LaLiga"))
        assertEquals(setOf("ES: M+ LIGA DE CAMPEONES", "ES: M+ LIGA DE CAMPEONES 2"), r.names("Spain", "Movistar Plus+", "europe/uefa-champions-league", "Champions League"))
        // DAZN in Spain: Spain's DAZN LaLiga, not Germany's DAZN.
        assertEquals(setOf("ES: DAZN LALIGA"), r.names("Spain", "DAZN", "spain/primera-division", "LaLiga"))
        // Sky Go in the UK for the Premier League: Main Event and Premier League only.
        assertEquals(setOf("UK: Sky Sports Main Event", "UK: Sky Sports Premier League 4K"), r.names("United Kingdom", "Sky Go", "england/premier-league", "Premier League"))
        assertEquals(setOf("UK: TNT Sports 1", "UK: TNT Sports 2"), r.names("United Kingdom", "discovery+", "europe/uefa-champions-league", "Champions League"))
        assertEquals(setOf("CH: blue Sport 1", "CH: blue Sport 2"), r.names("Switzerland", "blue Sport", null, null))
        assertEquals(setOf("SE: V Sport Fotboll"), r.names("Sweden", "Viaplay", null, null))
        assertEquals(setOf("US: CBS Sports Golazo"), r.names("USA", "Paramount+", null, "Champions League"))
        assertEquals(setOf("US: ESPN", "US: ESPN 2"), r.names("USA", "ESPN+", "spain/primera-division", "LaLiga"))
        assertEquals(setOf("CA: TSN1", "CA: TSN 4"), r.names("Canada", "TSN+", null, null))
        assertEquals(setOf("FR: CANAL+ FOOT"), r.names("France", "Canal+", "europe/uefa-champions-league", "Champions League").filter { "FOOT" in it }.toSet())
        assertEquals(false, r.names("France", "Canal+", null, null).contains("FR: CANAL+ CINEMA"))
        // A named channel is still matched exactly, in its own country only.
        assertEquals(setOf("FR: beIN SPORTS 1"), r.names("France", "beIN SPORTS 1", null, null))
    }

    @Test fun teamNames() {
        val k = dev.k3be.restv.data.Teams::key
        assertEquals(k("Barcelona"), k("FC Barcelona"))
        assertEquals(k("PSG"), k("Paris Saint-Germain"))
        assertEquals(k("Bayern Munich"), k("Bayern München"))
        assertEquals(k("AC Milan"), k("Milan"))
        assertEquals(k("Atletico Madrid"), k("Atlético de Madrid"))
        assertEquals(k("Al Hilal"), k("Al-Hilal"))
        assertEquals(k("Fenerbahce"), k("Fenerbahçe"))
        assertEquals(false, k("Real Madrid") == k("Real Madrid Castilla"))
        assertEquals(false, k("Iraq") == k("Iraq U23"))
    }

    @Test fun favouritesLeadTheList() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = Prefs(ctx)
        prefs.setFavoriteTeams(listOf("Real Madrid", "Iraq"))
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = MatchesRepo(OkHttpClient(), Json, db, prefs, ctx.filesDir)
        assertEquals(-1, repo.priority(Match("a", null, "World Cup Qualifying", "Iraq", "Jordan", emptyList())))
        assertEquals(-1, repo.priority(Match("b", null, "LaLiga", "Getafe", "Real Madrid CF", emptyList())))
        assertEquals(99, repo.priority(Match("c", null, "Friendly", "Iraq U23", "Oman U23", emptyList())))
        assertEquals("ucl", dev.k3be.restv.data.Broadcasters.competition("europe/uefa-champions-league", "UEFA Champions League"))
        assertEquals("laliga", dev.k3be.restv.data.Broadcasters.competition("spain/primera-division", "LaLiga"))
        assertEquals("intl", dev.k3be.restv.data.Broadcasters.competition("international/world-cup-qualifying-asia", "World Cup Qualifying"))
    }
}
