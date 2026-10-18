package dev.k3be.restv.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Holds decoded audio back by [delayMs] using a ring buffer of PCM.
 * The commentary streams run 60–90 s ahead of IPTV video, so the audio is delayed to meet the picture.
 * Changing the delay just moves the read position inside the history, so nudges are instant.
 * The history survives flushes (reconnects); [clear] wipes it when the source changes.
 */
@UnstableApi
class DelayAudioProcessor(private val maxSeconds: Int = 150) : BaseAudioProcessor() {

    @Volatile var delayMs: Long = 0

    private var ring = ByteArray(0)
    private var frameSize = 4
    private var sampleRate = 48_000
    @Volatile private var written = 0L
    @Volatile private var clearRequested = true

    /** Seconds of audio received so far (caps at the buffer size). */
    val receivedMs: Long
        get() = if (sampleRate == 0 || frameSize == 0) 0 else written * 1000 / (sampleRate.toLong() * frameSize)

    fun clear() {
        clearRequested = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        val f = inputAudioFormat
        val newFrame = f.bytesPerFrame
        val newRate = f.sampleRate
        val capacity = newRate * newFrame * maxSeconds
        if (ring.size != capacity || newFrame != frameSize || newRate != sampleRate) {
            ring = ByteArray(capacity)
            frameSize = newFrame
            sampleRate = newRate
            clearRequested = true
        }
        if (clearRequested) {
            written = 0
            clearRequested = false
        }
    }

    override fun onReset() {
        ring = ByteArray(0)
        written = 0
        clearRequested = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0 || ring.isEmpty()) {
            if (n > 0) { replaceOutputBuffer(n).put(inputBuffer).flip() }
            return
        }
        val cap = ring.size
        val start = written

        // Write the new audio into the ring.
        var pos = (start % cap).toInt()
        var left = n
        while (left > 0) {
            val chunk = minOf(left, cap - pos)
            inputBuffer.get(ring, pos, chunk)
            left -= chunk
            pos = (pos + chunk) % cap
        }
        written = start + n

        // Read the same amount from `delay` earlier; silence until enough history exists.
        val maxDelayBytes = (cap - n).toLong().coerceAtLeast(0)
        var delayBytes = (delayMs * sampleRate / 1000) * frameSize
        delayBytes = delayBytes.coerceIn(0, maxDelayBytes)
        val readStart = start - delayBytes
        val out = replaceOutputBuffer(n)
        var i = 0L
        while (i < n) {
            val p = readStart + i
            if (p < 0) {
                val zeros = minOf(-p, n - i).toInt()
                repeat(zeros) { out.put(0) }
                i += zeros
            } else {
                val rp = (p % cap).toInt()
                val chunk = minOf(n - i, (cap - rp).toLong()).toInt()
                out.put(ring, rp, chunk)
                i += chunk
            }
        }
        out.flip()
    }
}
