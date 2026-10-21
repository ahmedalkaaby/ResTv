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
