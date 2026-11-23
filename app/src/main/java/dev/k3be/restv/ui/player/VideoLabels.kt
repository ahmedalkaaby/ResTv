package dev.k3be.restv.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/** "Dolby Vision", "HDR10", "HDR10+", "HLG", or null for SDR — read from the stream being decoded. */
@OptIn(UnstableApi::class)
fun dynamicRange(f: Format?): String? {
    f ?: return null
    val codecs = f.codecs?.lowercase().orEmpty()
    if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || codecs.startsWith("dvh") || codecs.startsWith("dav1") || codecs.startsWith("dva")) return "Dolby Vision"
    return when (f.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }
}

/** What the TV says it can show: e.g. "Dolby Vision, HDR10, HLG"; null if it reports none. */
@Suppress("DEPRECATION")
fun displayHdr(context: Context): String? {
    val d = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
    val types: IntArray = d.hdrCapabilities?.supportedHdrTypes ?: return null
    return types.toList().mapNotNull {
        when (it) {
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
            Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
            Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
            else -> null
        }
    }.distinct().joinToString(", ").ifEmpty { null }
}

fun audioLabel(f: Format?): String? {
    f ?: return null
    val codec = when (f.sampleMimeType) {
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        else -> null
    }
    val ch = when (f.channelCount) { 6 -> "5.1"; 8 -> "7.1"; 2 -> "Stereo"; 1 -> "Mono"; else -> null }
    return listOfNotNull(codec, ch).joinToString(" ").ifEmpty { null }
}

fun resolutionLabel(w: Int, h: Int): String? = when {
    w <= 0 || h <= 0 -> null
    w >= 3200 || h >= 2000 -> "4K"
    h >= 1000 || w >= 1900 -> "1080p"
    h >= 700 || w >= 1260 -> "720p"
    else -> "SD"
}

/** "Dolby Digital+ · 5.1 · 640 kbps · 48 kHz" for the audio list. */
fun audioDetail(f: Format): String {
    val codec = when (f.sampleMimeType) {
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (DD+)"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        else -> f.codecs ?: f.sampleMimeType?.substringAfter('/')?.uppercase()
    }
    val ch = when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; in 3..16 -> "${f.channelCount} ch"; else -> null }
    val rate = f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" }
    val hz = f.sampleRate.takeIf { it > 0 }?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "%.1f kHz".format(it / 1000f) }
    return listOfNotNull(codec, ch, rate, hz).joinToString("  ·  ")
}
