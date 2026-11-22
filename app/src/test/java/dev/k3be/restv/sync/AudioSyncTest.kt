package dev.k3be.restv.sync

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.k3be.restv.ui.player.SoundTap
import dev.k3be.restv.ui.player.SoundTrace
import dev.k3be.restv.ui.player.SyncEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A synthetic match: one stadium (crowd that swells, whistles), heard by the channel with an
 * English commentator and by the commentary feed with a louder Arabic one, 73.4 s earlier.
 */
class AudioSyncTest {
    private val rate = 16_000

    /** Stadium sound: a crowd that swells and settles at irregular moments, whistles of varied length. */
    private class Stadium(seed: Long, seconds: Int, rate: Int) {
        val samples = FloatArray(seconds * rate)
        init {
            val rnd = java.util.Random(seed)
            val noise = java.util.Random(seed + 1)
            var level = 0.2; var target = 0.2; var nextChange = 0
            var whistleLeft = 0; var nextWhistle = rate * 3
            for (i in samples.indices) {
                if (i >= nextChange) { target = 0.05 + rnd.nextDouble() * rnd.nextDouble() * 0.7; nextChange = i + (rate * (0.3 + rnd.nextDouble() * 2.5)).toInt() }
                level += (target - level) * (8.0 / rate) // swells over ~1/8 s
                var x = noise.nextGaussian() * level * 0.3
                if (i >= nextWhistle) { whistleLeft = (rate * (0.2 + rnd.nextDouble())).toInt(); nextWhistle = i + (rate * (5 + rnd.nextDouble() * 25)).toInt() }
                if (whistleLeft > 0) { x += 0.25 * sin(2 * PI * 3100.0 * i / rate); whistleLeft-- }
                samples[i] = x.toFloat()
            }
        }
    }

    /**
     * A commentator: noise shaped to the speech band (≈200–3500 Hz) with smooth syllables
     * (about 4 a second) and pauses between phrases; [level] is its loudness against the crowd.
     */
    private fun voice(seed: Long, n: Int, level: Float): FloatArray {
        if (level == 0f) return FloatArray(n)
        val rnd = java.util.Random(seed)
        val out = FloatArray(n)
        // Speech-band noise: difference of two one-pole low-passes.
        var lpHi = 0.0; var lpLo = 0.0
        val aHi = 1 - kotlin.math.exp(-2 * PI * 3500.0 / rate); val aLo = 1 - kotlin.math.exp(-2 * PI * 200.0 / rate)
        var syll = rate / 4; var syllPos = 0
        var phraseLeft = 0; var talking = true
        for (i in 0 until n) {
            if (phraseLeft <= 0) { talking = !talking; phraseLeft = (rate * (if (talking) 1.5 + rnd.nextDouble() * 4.5 else 0.25 + rnd.nextDouble() * 1.2)).toInt() }
            phraseLeft--
            val w = rnd.nextGaussian()
            lpHi += aHi * (w - lpHi); lpLo += aLo * (w - lpLo)
            if (++syllPos >= syll) { syllPos = 0; syll = (rate * (0.12 + rnd.nextDouble() * 0.23)).toInt() }
            val env = if (talking) sin(PI * syllPos / syll).let { it * it } else 0.0
            out[i] = (level * 3.0 * (lpHi - lpLo) * env).toFloat()
        }
        return out
    }

    private fun feed(tap: SoundTap, signal: FloatArray, startWallMs: Long) {
        // Drive the tap with a fake clock by writing slots directly via its own analysis path:
        // the tap stamps with System.currentTimeMillis, so for a test we write traces from a twin.
        val fmt = AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT)
        tap.configure(fmt); tap.flush(); tap.enabled = true
        val chunk = rate / 10
        var i = 0
        while (i < signal.size) {
            val n = minOf(chunk, signal.size - i)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (k in 0 until n) buf.putShort((signal[i + k].coerceIn(-1f, 1f) * 32000).toInt().toShort())
            buf.flip()
            TestClock.now = startWallMs + i * 1000L / rate
            tap.queueInput(buf)
            tap.getOutput()
            i += n
        }
    }

    object TestClock { var now = 0L }

    @Test fun noCommentators() = lead(0f, 0f)
    @Test fun commentatorsAsLoudAsCrowd() = lead(0.06f, 0.04f)
    @Test fun commentatorsTwiceTheCrowd() = lead(0.12f, 0.08f)
    @Test fun commentatorsThreeTimesTheCrowd() = lead(0.18f, 0.12f)

    private fun lead(arabic: Float, english: Float) {
        val lead = 73_400L
        val total = 200
        val stadium = Stadium(5, total, rate)
        val leadSamples = (lead * rate / 1000).toInt()
        // Commentary hears stadium sample s at time s; the channel hears it at s + lead.
        val commN = total * rate
        val comm = FloatArray(commN) { stadium.samples[it] * 0.6f }
        val v1 = voice(11, commN, arabic)
        for (i in comm.indices) comm[i] += v1[i]
        val videoN = commN - leadSamples
        val video = FloatArray(videoN) { stadium.samples[it] }
        val v2 = voice(12, videoN, english)
        for (i in video.indices) video[i] += v2[i]

        val vt = SoundTrace(); val ct = SoundTrace()
        trace(ct, comm, 0L)
        trace(vt, video, lead)
        val now = (lead + videoN * 1000L / rate) / SoundTrace.SLOT_MS - 4
        val est = SyncEstimator.estimate(vt, ct, now, 40_000, 140_000)!!
        fun rms(x: FloatArray) = kotlin.math.sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size)
        println("arabic=$arabic english=$english voice/crowd=${"%.1f".format(rms(v1) / rms(stadium.samples))} -> $est")
        assertTrue("strength ${est.strength}", est.confident)
        assertTrue("got ${est.delayMs}", abs(est.delayMs - lead) <= 80)
    }

    @Test fun unrelatedSoundIsNotAMatch() = repeat(3) { seed -> unrelated(seed.toLong()) }

    private fun unrelated(seed: Long) {
        val a = Stadium(100 + seed, 200, rate).samples
        val b = Stadium(200 + seed, 200, rate).samples
        val va = voice(300 + seed, b.size, 0.1f); for (i in b.indices) b[i] += va[i]
        val vt = SoundTrace(); val ct = SoundTrace()
        trace(ct, b, 0L)
        trace(vt, a.copyOfRange(0, 120 * rate), 80_000L)
        val now = 200_000L / SoundTrace.SLOT_MS - 4
        val est = SyncEstimator.estimate(vt, ct, now, 40_000, 140_000)!!
        println("unrelated $seed -> $est")
        assertFalse("strength ${est.strength}", est.confident)
    }

    @Test fun tapPassesSoundThroughUnchanged() {
        val tap = SoundTap()
        tap.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)); tap.flush(); tap.enabled = true
        val buf = ByteBuffer.allocateDirect(4000).order(ByteOrder.nativeOrder())
        repeat(1000) { buf.putShort((it * 7).toShort()); buf.putShort((-it).toShort()) }
        buf.flip()
        val copy = ByteArray(4000); buf.duplicate().get(copy)
        tap.queueInput(buf)
        val out = tap.getOutput(); val got = ByteArray(out.remaining()); out.get(got)
        assertEquals(copy.toList(), got.toList())
    }

    /** Same analysis as the tap, with time taken from the sample position. */
    private fun trace(t: SoundTrace, signal: FloatArray, startMs: Long) {
        val tap = SoundTap(t)
        tap.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT)); tap.flush(); tap.enabled = true
        tap.clock = { TestClock.now }
        feed(tap, signal, startMs)
    }
}
