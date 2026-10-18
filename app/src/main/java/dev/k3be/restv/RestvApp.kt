package dev.k3be.restv

import android.app.Application
import dev.k3be.restv.data.AudioSources
import dev.k3be.restv.data.EpgCache
import dev.k3be.restv.data.MatchesRepo
import dev.k3be.restv.data.Prefs
import dev.k3be.restv.data.SyncEngine
import dev.k3be.restv.data.db.AppDatabase
import dev.k3be.restv.data.xtream.XtreamClient
import dev.k3be.restv.update.Updater
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Holds the app's single instances. Small enough that a DI framework isn't worth it yet. */
class RestvApp : Application() {

    val json: Json by lazy { Json { ignoreUnknownKeys = true; isLenient = true } }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Separate client for streams: shorter read timeout so a stalled stream fails fast and reconnects. */
    val streamHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val xtream: XtreamClient by lazy { XtreamClient(http, json) }
    val sync: SyncEngine by lazy { SyncEngine(db, xtream, cacheDir) }
    val epg: EpgCache by lazy { EpgCache(xtream) }
    val prefs: Prefs by lazy { Prefs(this) }
    val updater: Updater by lazy { Updater(this, http, json) }
    val audioSources: AudioSources by lazy { AudioSources(this, prefs) }
    val matches: MatchesRepo by lazy { MatchesRepo(http, json, db, prefs) }
}
