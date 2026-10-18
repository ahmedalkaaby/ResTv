package dev.k3be.restv.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.k3be.restv.data.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed interface CommentaryState {
    data object Off : CommentaryState
    /** Collecting audio until enough is buffered to play [delay] behind live. */
    data class Filling(val secondsLeft: Int) : CommentaryState
    data object Playing : CommentaryState
    data class Reconnecting(val attempt: Int) : CommentaryState
    data class Failed(val message: String) : CommentaryState
}

/** Plays an external commentary stream through [DelayAudioProcessor], reconnecting on drops. */
@OptIn(UnstableApi::class)
class CommentaryPlayer(
    context: Context,
    http: OkHttpClient,
    userAgent: String,
    private val scope: CoroutineScope,
) {
    private val delayProcessor = DelayAudioProcessor()
    private val player: ExoPlayer

    private val _state = MutableStateFlow<CommentaryState>(CommentaryState.Off)
    val state: StateFlow<CommentaryState> = _state

    private val _delayMs = MutableStateFlow(75_000L)
    val delayMs: StateFlow<Long> = _delayMs

    var source: AudioSource? = null
        private set
    private var useLow = false
    private var failures = 0
    private var retryJob: Job? = null
    private val ticker: Job

    init {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(delayProcessor))
                .build()
        }
        val upstream = OkHttpDataSource.Factory(http).setUserAgent(userAgent)
        player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, upstream)))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) reconnect()
                if (state == Player.STATE_READY) {
                    failures = 0
                    if (_state.value is CommentaryState.Reconnecting) _state.value = CommentaryState.Playing
                }
            }

            override fun onPlayerError(error: PlaybackException) = reconnect()
        })
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                val s = _state.value
                if (source == null || s is CommentaryState.Failed || s is CommentaryState.Reconnecting) continue
                val left = (_delayMs.value - delayProcessor.receivedMs) / 1000
                _state.value = if (left > 0) CommentaryState.Filling(left.toInt()) else CommentaryState.Playing
            }
        }
    }

    fun start(src: AudioSource, delayMs: Long) {
        retryJob?.cancel()
        source = src
        useLow = false
        failures = 0
        setDelay(delayMs)
        delayProcessor.clear()
        _state.value = CommentaryState.Filling((delayMs / 1000).toInt())
        load()
    }

    fun stop() {
        retryJob?.cancel()
        source = null
        player.stop()
        player.clearMediaItems()
        delayProcessor.clear()
        _state.value = CommentaryState.Off
    }

    fun setDelay(ms: Long) {
        val v = ms.coerceIn(0, 140_000)
        _delayMs.value = v
        delayProcessor.delayMs = v
    }

    fun nudge(deltaMs: Long) = setDelay(_delayMs.value + deltaMs)

    private fun load() {
        val src = source ?: return
        val url = if (useLow && src.lowUrl != null) src.lowUrl else src.url
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun reconnect() {
        val src = source ?: return
        if (retryJob?.isActive == true) return
        failures++
        if (failures > 8) {
            _state.value = CommentaryState.Failed("${src.name} isn't responding. The playlist token may have changed.")
            return
        }
        // Switch to the low-bitrate twin after a few failures.
        if (failures == 3 && src.lowUrl != null) useLow = !useLow
        _state.value = CommentaryState.Reconnecting(failures)
        retryJob = scope.launch {
            delay((500L shl (failures - 1).coerceAtMost(4)).coerceAtMost(8_000))
            load()
        }
    }

    fun release() {
        retryJob?.cancel()
        ticker.cancel()
        player.release()
    }
}
