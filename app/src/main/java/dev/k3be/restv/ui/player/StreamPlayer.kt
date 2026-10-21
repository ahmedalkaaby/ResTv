package dev.k3be.restv.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class StreamSource(
    val key: Long,
    val tsUrl: String,
    val hlsUrl: String,
    val format: String,
)

sealed interface PlayStatus {
    data object Starting : PlayStatus
    data object Playing : PlayStatus
    data object Buffering : PlayStatus
    data class Reconnecting(val attempt: Int, val reason: String) : PlayStatus
    data class Failed(val message: String) : PlayStatus
}

data class VideoInfo(
    val width: Int,
    val height: Int,
    val fps: Float,
    val codec: String?,
    val audio: String?,
    /** "HDR10", "Dolby Vision", "HLG"; null for SDR. */
    val range: String? = null,
) {
    val resolution: String get() = "${width}×${height}"
}

/**
 * ExoPlayer tuned for live IPTV: bigger buffers for 4K, automatic reconnect with backoff,
 * a stall watchdog, and a ts → m3u8 fallback that is remembered per channel.
 */
@OptIn(UnstableApi::class)
class StreamPlayer(
    context: Context,
    http: OkHttpClient,
    userAgent: String,
    private val scope: CoroutineScope,
    private val onFormatChanged: (key: Long, format: String) -> Unit,
    /** Called when a stream keeps failing, so the caller can try another quality of the same channel. */
    private val onGiveUp: (key: Long) -> Unit = {},
) {
    /** Failures allowed before giving up. Lower when a fallback quality exists. */
    var giveUpAfter: Int = 10
    val player: ExoPlayer

    private val _status = MutableStateFlow<PlayStatus>(PlayStatus.Starting)
    val status: StateFlow<PlayStatus> = _status

    private val _video = MutableStateFlow<VideoInfo?>(null)
    val video: StateFlow<VideoInfo?> = _video

    private var source: StreamSource? = null
    private var format = "ts"
    private var failures = 0
    private var bufferingSince = 0L
    private var playingSince = 0L
    private var retryJob: Job? = null
    private val watchdog: Job
    private val listener: Player.Listener = createListener()

    init {
        val upstream = OkHttpDataSource.Factory(http).setUserAgent(userAgent)
        val dataSource = DefaultDataSource.Factory(context, upstream)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(loadControl)
            .build()
        player.playWhenReady = true
        player.addListener(listener)

        watchdog = scope.launch {
            while (isActive) {
                delay(2_000)
                val now = System.currentTimeMillis()
                if (player.playbackState == Player.STATE_BUFFERING && bufferingSince > 0 && now - bufferingSince > 15_000) {
                    reconnect("Stalled for 15 s")
                }
                // A stream that has played steadily for a minute resets the failure count.
                if (player.isPlaying && playingSince > 0 && now - playingSince > 60_000) failures = 0
            }
        }
    }

    fun play(src: StreamSource) {
        retryJob?.cancel()
        source = src
        format = src.format
        failures = 0
        _video.value = null
        _status.value = PlayStatus.Starting
        load()
    }

    private fun load() {
        val src = source ?: return
        val hls = format == "m3u8"
        val item = MediaItem.Builder()
            .setUri(if (hls) src.hlsUrl else src.tsUrl)
            .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    private fun reconnect(reason: String) {
        if (retryJob?.isActive == true) return
        failures++
        val src = source ?: return
        if (failures > giveUpAfter) {
            _status.value = PlayStatus.Failed("This channel isn't responding. $reason")
            onGiveUp(src.key)
            return
        }
        // After three failures on MPEG-TS, try HLS and keep whichever works for this channel.
        if (failures == 3) {
            format = if (format == "ts") "m3u8" else "ts"
            onFormatChanged(src.key, format)
        }
        _status.value = PlayStatus.Reconnecting(failures, reason)
        val wait = (500L shl (failures - 1).coerceAtMost(4)).coerceAtMost(8_000)
        retryJob = scope.launch {
            delay(wait)
            load()
        }
    }

    private fun createListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val now = System.currentTimeMillis()
            when (state) {
                Player.STATE_BUFFERING -> {
                    if (bufferingSince == 0L) bufferingSince = now
                    if (_status.value !is PlayStatus.Reconnecting) _status.value = PlayStatus.Buffering
                }
                Player.STATE_READY -> {
                    bufferingSince = 0
                    if (playingSince == 0L) playingSince = now
                    _status.value = PlayStatus.Playing
                }
                // Live MPEG-TS "ends" when the panel drops the connection: reconnect instead of stopping.
                Player.STATE_ENDED -> {
                    bufferingSince = 0
                    playingSince = 0
                    reconnect("The server closed the stream")
                }
                Player.STATE_IDLE -> {
                    bufferingSince = 0
                    playingSince = 0
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            bufferingSince = 0
            playingSince = 0
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            reconnect(error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase())
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateVideo()
        override fun onTracksChanged(tracks: Tracks) = updateVideo()
    }

    private fun updateVideo() {
        val f = player.videoFormat ?: return
        val a = player.audioFormat
        val w = if (f.width > 0) f.width else player.videoSize.width
        val h = if (f.height > 0) f.height else player.videoSize.height
        if (w <= 0 || h <= 0) return
        _video.value = VideoInfo(
            width = w,
            height = h,
            fps = f.frameRate,
            codec = codecName(f.sampleMimeType),
            audio = a?.let { audioName(it.sampleMimeType, it.channelCount) },
            range = dynamicRange(f),
        )
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        null -> null
        else -> mime.substringAfter('/').uppercase()
    }

    private fun audioName(mime: String?, channels: Int): String {
        val codec = when (mime) {
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_E_AC3 -> "E-AC3"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            else -> mime?.substringAfter('/')?.uppercase() ?: "Audio"
        }
        val ch = when (channels) {
            -1, 0 -> ""
            1 -> " · Mono"
            2 -> " · Stereo"
            6 -> " · 5.1"
            8 -> " · 7.1"
            else -> " · ${channels}ch"
        }
        return codec + ch
    }

    fun release() {
        retryJob?.cancel()
        watchdog.cancel()
        player.removeListener(listener)
        player.release()
    }
}
