package dev.k3be.restv.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Matching the commentary to the picture by ear.
 *
 * The Arabic commentary feed carries the same stadium sound as the channel (crowd swells, whistles,
 * the ball being struck) under the commentator's voice. Both streams are reduced to a light
 * "loudness changes per frequency band" trace, 40 times a second, stamped with the time it was
 * heard. Sliding one trace over the other, the delay where they line up is the delay to use.
 */

/** Loudness traces, one value per band per 25 ms slot of wall-clock time. */
class SoundTrace(private val seconds: Int = 260) {
    val slotMs = SLOT_MS
    private val cap = seconds * 1000 / SLOT_MS
    private val slot = LongArray(cap) { -1 }
    private val value = FloatArray(cap * BANDS)

    @Synchronized fun put(slotIndex: Long, energies: FloatArray) {
        val i = (slotIndex % cap).toInt()
        slot[i] = slotIndex
        System.arraycopy(energies, 0, value, i * BANDS, BANDS)
    }

    @Synchronized fun clear() { slot.fill(-1) }

    /** Copies slots [from, to) out; missing slots are NaN. */
    @Synchronized fun read(from: Long, to: Long): Array<FloatArray> = Array((to - from).toInt()) { k ->
        val s = from + k
        val i = (Math.floorMod(s, cap.toLong())).toInt()
        if (slot[i] == s) FloatArray(BANDS) { value[i * BANDS + it] } else FloatArray(BANDS) { Float.NaN }
    }

    /** First and last slot that hold sound, within the kept history. */
    @Synchronized fun span(): LongRange? {
        var lo = Long.MAX_VALUE; var hi = Long.MIN_VALUE
        for (s in slot) if (s >= 0) { if (s < lo) lo = s; if (s > hi) hi = s }
        return if (lo == Long.MAX_VALUE) null else lo..hi
    }

    companion object {
        const val SLOT_MS = 25
        const val BANDS = 8
    }
}

/**
 * Pass-through audio processor that writes a [SoundTrace]. It never changes the sound.
 * Only 16-bit PCM is read; anything else (or [enabled] off) just passes.
 */
@UnstableApi
class SoundTap(val trace: SoundTrace = SoundTrace()) : BaseAudioProcessor() {
    @Volatile var enabled = false
    /** Wall clock; replaced only in tests. */
    var clock: () -> Long = System::currentTimeMillis

    private var rate = 48_000
    private var channels = 2
    private var filters = emptyArray<Biquad>()
    private val acc = DoubleArray(SoundTrace.BANDS)
    private var inHop = 0
    private var hop = 1200
    // Time line: sample count since an anchor in wall-clock time, so bursts of decoding don't squash it.
    private var anchorMs = -1L
    private var samplesSinceAnchor = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Anything but 16-bit PCM passes untouched (the processor stays inactive).
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        rate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 48_000
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        hop = rate * SoundTrace.SLOT_MS / 1000
        filters = CENTERS.map { Biquad.bandPass(it.coerceAtMost(rate * 0.45), 1.2, rate.toDouble()) }.toTypedArray()
        acc.fill(0.0); inHop = 0; anchorMs = -1
    }

    override fun onReset() { anchorMs = -1 }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        if (enabled && filters.isNotEmpty()) analyse(inputBuffer.duplicate().order(ByteOrder.nativeOrder()))
        replaceOutputBuffer(n).put(inputBuffer).flip()
    }

    private fun analyse(buf: ByteBuffer) {
        val now = clock()
        val frames = buf.remaining() / (2 * channels)
        // Re-anchor after a pause or a drift of more than half a second.
        val predicted = anchorMs + samplesSinceAnchor * 1000 / rate
        if (anchorMs < 0 || abs(now - predicted) > 500) { anchorMs = now; samplesSinceAnchor = 0 }
        val energies = FloatArray(SoundTrace.BANDS)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += buf.short.toInt()
            val x = sum.toDouble() / (channels * 32768.0)
            for (b in filters.indices) { val y = filters[b].step(x); acc[b] += y * y }
            samplesSinceAnchor++
            if (++inHop >= hop) {
                for (b in filters.indices) energies[b] = log10(acc[b] / hop + 1e-10).toFloat()
                val t = anchorMs + samplesSinceAnchor * 1000 / rate
                trace.put(t / SoundTrace.SLOT_MS, energies)
                acc.fill(0.0); inHop = 0
            }
        }
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        companion object {
            fun bandPass(f0: Double, q: Double, fs: Double): Biquad {
                val w = 2 * PI * f0 / fs
                val alpha = sin(w) / (2 * q)
                val a0 = 1 + alpha
                return Biquad(alpha / a0, 0.0, -alpha / a0, -2 * cos(w) / a0, (1 - alpha) / a0)
            }
        }
    }

    companion object {
        private val CENTERS = listOf(200.0, 400.0, 800.0, 1500.0, 2500.0, 3500.0, 5000.0, 7000.0)
    }
}

/**
 * Finds how far the commentary runs ahead of the channel.
 *
 * The commentator's voice covers most of the stadium sound, but speech has tiny gaps between
 * syllables, and in those gaps each band drops to the crowd underneath. So each band is reduced to
 * its floor (the quietest level over 0.4 s) and its rise and fall around the last 3 s: that is
 * the crowd, nearly free of voice. Each band votes separately, so bands where a voice still
 * dominates don't drown the clean ones.
 */
object SyncEstimator {
    /** [strength]: how far the best match stands above the next best elsewhere (1 = no better). */
    data class Estimate(val delayMs: Long, val strength: Float, val confident: Boolean)

    const val CONFIDENT = 1.9f
    private const val CONTEXT = 140 // slots read beyond the window, for the 3 s trend

    /**
     * Compares the channel's last [windowMs] with the commentary heard [minLagMs, maxLagMs] earlier.
     * [nowSlot] is the newest slot to use (a little behind real time so both traces are written).
     */
    fun estimate(video: SoundTrace, comm: SoundTrace, nowSlot: Long, windowMs: Long, maxLagMs: Long, minLagMs: Long = 0): Estimate? {
        val w = (windowMs / SoundTrace.SLOT_MS).toInt()
        val maxLag = (maxLagMs / SoundTrace.SLOT_MS).toInt()
        val minLag = (minLagMs / SoundTrace.SLOT_MS).toInt().coerceAtLeast(0)
        if (w < 200 || maxLag <= minLag + 120) return null
        val vAll = crowd(video.read(nowSlot - w - CONTEXT, nowSlot))
        val v = vAll.copyOfRange(vAll.size - w, vAll.size)
        val cAll = crowd(comm.read(nowSlot - w - maxLag - CONTEXT, nowSlot - minLag))
        val c = cAll.copyOfRange(CONTEXT, cAll.size)
        // v[t] lines up with c[t + (maxLag - lag)] for a commentary lead of `lag` slots.
        val lags = maxLag - minLag + 1
        val bands = SoundTrace.BANDS
        val total = FloatArray(lags)
        val score = FloatArray(lags)
        for (k in 0 until bands) {
            for (li in 0 until lags) {
                val off = maxLag - (minLag + li)
                var s = 0f; var n = 0
                for (t in 0 until w) {
                    val a = v[t][k]; val b = c[t + off][k]
                    if (a.isNaN() || b.isNaN()) continue
                    s += a * b; n++
                }
                score[li] = if (n > w / 2) s / n else 0f
            }
            // A true match is a sharp spike; judge each lag against its ±1 s neighbourhood.
            val sharp = detrend(score, 40)
            var m = 0.0; for (x in sharp) m += x; m /= sharp.size
            var q = 0.0; for (x in sharp) q += (x - m) * (x - m)
            val sd = sqrt(q / sharp.size)
            if (sd < 1e-9) continue
            for (i in sharp.indices) { val zz = ((sharp[i] - m) / sd).toFloat().coerceAtLeast(0f); total[i] += zz * zz }
        }
        var best = 0
        for (i in total.indices) if (total[i] > total[best]) best = i
        var mean = 0.0; for (x in total) mean += x; mean /= total.size
        var second = Float.NEGATIVE_INFINITY
        for (i in total.indices) if (abs(i - best) > 60 && total[i] > second) second = total[i]
        val strength = ((total[best] - mean) / (second - mean).coerceAtLeast(1e-9)).toFloat()
        // Sub-slot precision from the shape of the peak.
        var frac = 0.0
        if (best in 1 until total.lastIndex) {
            val l = total[best - 1]; val mm = total[best]; val r = total[best + 1]
            val den = l - 2 * mm + r
            if (den != 0f) frac = (0.5 * (l - r) / den).coerceIn(-0.5, 0.5)
        }
        val delay = ((minLag + best + frac) * SoundTrace.SLOT_MS).toLong()
        return Estimate(delay, strength, strength >= CONFIDENT)
    }

    /** Each value minus the average of its neighbours within [half] (itself and the next ones excluded). */
    private fun detrend(x: FloatArray, half: Int): FloatArray {
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        return FloatArray(x.size) { i ->
            val lo = (i - half).coerceAtLeast(0); val hi = (i + half + 1).coerceAtMost(x.size)
            val inLo = (i - 2).coerceAtLeast(lo); val inHi = (i + 3).coerceAtMost(hi)
            val sum = prefix[hi] - prefix[lo] - (prefix[inHi] - prefix[inLo])
            val n = (hi - lo) - (inHi - inLo)
            (x[i] - if (n > 0) sum / n else 0.0).toFloat()
        }
    }

    /** Per band: floor over 0.4 s, smoothed over 0.2 s, minus its 3 s trend, normalised. Gaps stay NaN. */
    internal fun crowd(rows: Array<FloatArray>): Array<FloatArray> {
        val n = rows.size
        val bands = SoundTrace.BANDS
        val out = Array(n) { FloatArray(bands) { Float.NaN } }
        val valid = BooleanArray(n) { !rows[it][0].isNaN() }
        val col = FloatArray(n)
        val floor = FloatArray(n)
        for (k in 0 until bands) {
            // Gaps take the last value heard, only so the filters run; they're left out below.
            var last = Float.NaN
            for (t in 0 until n) { if (valid[t]) last = rows[t][k]; col[t] = last }
            var first = 0; while (first < n && col[first].isNaN()) first++
            if (first == n) continue
            for (t in 0 until first) col[t] = col[first]
            for (t in 0 until n) {
                var mn = Float.MAX_VALUE
                for (j in (t - 8).coerceAtLeast(0)..(t + 7).coerceAtMost(n - 1)) if (col[j] < mn) mn = col[j]
                floor[t] = mn
            }
            val smooth = movingAverage(floor, 4)
            val trend = movingAverage(floor, 60)
            var s = 0.0; var c = 0
            for (t in 0 until n) if (valid[t]) { s += smooth[t] - trend[t]; c++ }
            if (c == 0) continue
            val m = s / c
            var q = 0.0; for (t in 0 until n) if (valid[t]) { val d = smooth[t] - trend[t] - m; q += d * d }
            val sd = sqrt(q / c).coerceAtLeast(1e-6)
            for (t in 0 until n) if (valid[t]) out[t][k] = ((smooth[t] - trend[t] - m) / sd).toFloat()
        }
        return out
    }

    private fun movingAverage(x: FloatArray, half: Int): FloatArray {
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        return FloatArray(x.size) { i ->
            val lo = (i - half).coerceAtLeast(0); val hi = (i + half + 1).coerceAtMost(x.size)
            ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
        }
    }
}

sealed interface SyncState {
    data object Off : SyncState
    data class Listening(val secondsLeft: Int) : SyncState
    data class Synced(val delayMs: Long) : SyncState
    data object NoMatch : SyncState
    data object NoChannelSound : SyncState
}

/**
 * Keeps the commentary matched: a first fix once enough of both streams has been heard
 * (about half a minute after the commentary starts playing), then a check every 30 s that
 * follows the channel if it stalls and slips.
 */
@UnstableApi
class AutoSync(
    private val scope: CoroutineScope,
    private val video: SoundTap,
    private val comm: SoundTap,
    private val currentDelay: () -> Long,
    private val apply: (Long) -> Unit,
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Off)
    val state: StateFlow<SyncState> = _state
    private var job: Job? = null
    @Volatile private var manualAt = 0L

    /** A nudge by hand wins: no corrections for the next 2 minutes. */
    fun userAdjusted() { manualAt = System.currentTimeMillis() }

    fun start() {
        job?.cancel()
        video.trace.clear(); comm.trace.clear()
        video.enabled = true; comm.enabled = true
        _state.value = SyncState.Listening(WINDOW_MS.toInt() / 1000 + 60)
        val started = System.currentTimeMillis()
        job = scope.launch {
            var last: SyncEstimator.Estimate? = null
            var synced = false
            while (isActive) {
                delay(if (synced) 30_000 else 5_000)
                val now = System.currentTimeMillis()
                val nowSlot = now / SoundTrace.SLOT_MS - 20 // half a second back: both traces written
                val vs = video.trace.span(); val cs = comm.trace.span()
                if (vs == null || vs.last < nowSlot - 400) {
                    if (now - started > 15_000) _state.value = SyncState.NoChannelSound
                    continue
                }
                if (cs == null) continue
                val heardV = (nowSlot - vs.first) * SoundTrace.SLOT_MS
                val heardC = (nowSlot - cs.first) * SoundTrace.SLOT_MS
                val window = minOf(WINDOW_MS, heardV)
                val maxLag = minOf(MAX_LAG_MS, heardC - window)
                // Enough to test the delay in use (plus margin), or the whole range.
                val needed = minOf(MAX_LAG_MS, currentDelay() + 15_000)
                if (window < 25_000 || maxLag < needed) {
                    if (!synced) {
                        val left = maxOf(25_000 - window, needed - maxLag).coerceAtLeast(0) / 1000
                        _state.value = SyncState.Listening(left.toInt() + 5)
                    }
                    continue
                }
                val est = withContext(Dispatchers.Default) {
                    SyncEstimator.estimate(video.trace, comm.trace, nowSlot, window, maxLag)
                } ?: continue
                if (!est.confident) {
                    last = null
                    if (!synced && now - started > 240_000) _state.value = SyncState.NoMatch
                    continue
                }
                // Two readings that agree, or one very clear one, before moving anything.
                val agrees = last?.let { abs(it.delayMs - est.delayMs) <= 200 } == true
                if (agrees || est.strength >= 3f) {
                    val byHand = System.currentTimeMillis() - manualAt < 120_000
                    if (!byHand && abs(est.delayMs - currentDelay()) > 150) apply(est.delayMs)
                    synced = true
                    _state.value = SyncState.Synced(est.delayMs)
                }
                last = est
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        video.enabled = false; comm.enabled = false
        _state.value = SyncState.Off
    }

    companion object {
        const val WINDOW_MS = 40_000L
        const val MAX_LAG_MS = 140_000L
    }
}
