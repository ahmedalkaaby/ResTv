package dev.k3be.restv.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Feeds mono 16-bit PCM where each sample holds its own index, so the output shows exactly how far back it reads. */
class DelayAudioProcessorTest {
    private val rate = 8_000 // 0.1 s = 800 samples

    private fun feed(p: DelayAudioProcessor, from: Int, count: Int): ShortArray {
        val buf = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until count) buf.putShort(((from + i) % 30_000).toShort())
        buf.flip()
        p.queueInput(buf)
        val out = p.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test fun nudgesMoveTheAudioByExactlyTheStep() {
        val p = DelayAudioProcessor(maxSeconds = 5)
        p.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        p.flush()
        p.delayMs = 1_000
        feed(p, 0, 16_000) // two seconds of history
        // With a 1 s delay, the next chunk starts 8 000 samples back.
        assertEquals((16_000 - 8_000).toShort(), feed(p, 16_000, 400)[0])
        p.delayMs = 1_100 // +0.1 s
        assertEquals((16_400 - 8_800).toShort(), feed(p, 16_400, 400)[0])
        p.delayMs = 1_000 // −0.1 s
        assertEquals((16_800 - 8_000).toShort(), feed(p, 16_800, 400)[0])
    }
}
