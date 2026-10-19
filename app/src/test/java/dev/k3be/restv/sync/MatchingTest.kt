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
}
