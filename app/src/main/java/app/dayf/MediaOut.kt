package app.dayf

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max

/**
 * Plays an audio file through the SAME channel as the agent's voice (AgentPlayer), so it follows the same route into the live,
 * the same volume and the same ducking. Any format Android can decode works (mp3, m4a/aac, ogg/opus, flac, wav, amr...).
 */
object MediaOut {
    private const val OUT_RATE = 24000
    private const val CHUNK = 2400 // 100 ms of samples per queued block

    @Volatile var playing = false
        private set
    @Volatile var title = ""
        private set
    @Volatile var srcId = ""
        private set
    @Volatile var durationSecs = 0
        private set
    @Volatile var paused = false
        private set
    val active: Boolean get() = playing || paused
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Set by the running session: lets the buttons tell the agent what the host just did. */
    @Volatile var notifyAgent: ((String) -> Unit)? = null

    private class Resume(
        val player: AgentPlayer, val file: File, val title: String, val srcId: String, val secs: Int, val outline: String,
        val onEnd: (Boolean) -> Unit, var pos: Int
    )

    @Volatile private var cur: Resume? = null
    private var loudMs = 0

    private fun fire() {
        for (l in listeners) try { l() } catch (e: Exception) { }
    }

    @Volatile private var gen = 0
    @Volatile private var fed = 0L
    @Volatile private var startSec = 0
    @Volatile private var sections: List<Pair<Int, Int>> = emptyList()
    private var pl: AgentPlayer? = null

    fun durationSecs(f: File): Int {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.path)
            var d = 0L
            for (i in 0 until ex.trackCount) {
                val fm = ex.getTrackFormat(i)
                if ((fm.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/") && fm.containsKey(MediaFormat.KEY_DURATION)) d = fm.getLong(MediaFormat.KEY_DURATION)
            }
            (d / 1_000_000L).toInt()
        } catch (e: Exception) {
            0
        } finally {
            try { ex.release() } catch (e: Exception) { }
        }
    }

    /** "[MM:SS] topic (جزء n)" lines of a source outline → (seconds, part number). */
    fun parseSections(outline: String): List<Pair<Int, Int>> {
        val re = Regex("\\[(\\d+):(\\d{2})].*\\(جزء (\\d+)")
        return outline.lines().mapNotNull { l ->
            val m = re.find(l) ?: return@mapNotNull null
            (m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()) to m.groupValues[3].toInt()
        }
    }

    /** Part (1-based) of the notes that covers the given second. */
    fun partAt(sec: Int): Int? = sections.lastOrNull { it.first <= sec }?.second ?: sections.firstOrNull()?.second

    fun positionSecs(): Int {
        if (paused) return cur?.pos ?: startSec
        val p = pl ?: return startSec
        val played = (fed - p.queued().toLong() * CHUNK) / OUT_RATE.toDouble() - 0.4
        return startSec + max(0.0, played).toInt()
    }

    private fun halt() {
        gen++
        val was = playing
        playing = false
        if (was) try { pl?.flush() } catch (e: Exception) { }
    }

    /** Full stop: nothing to resume afterwards. */
    fun stop() {
        val had = active
        halt()
        paused = false
        cur = null
        if (had) fire()
    }

    /** Pauses and remembers where it stopped. Returns the position in seconds, or -1 when nothing was playing. */
    fun pause(): Int {
        val c = cur
        if (!playing || c == null) return -1
        val pos = positionSecs()
        halt()
        c.pos = pos
        paused = true
        fire()
        return pos
    }

    /** Continues from where pause() stopped. */
    fun resume(): Boolean {
        val c = cur
        if (!paused || c == null) return false
        paused = false
        return play(c.player, c.file, c.pos, c.title, c.srcId, c.secs, c.outline, c.onEnd)
    }

    fun mmss(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    /** The buttons (floating panel and studio): toggles and tells the agent, so voice and buttons never disagree. */
    fun toggleByUser() {
        if (playing) {
            val pos = pause()
            if (pos >= 0) notifyAgent?.invoke("[حالة النظام] أوقف المضيف الصوت مؤقتاً عند ${mmss(pos)}. استمع له وأجبه، ثم استأنف بـ resume_audio حين يطلب المتابعة. لا تعلّق على الإيقاف نفسه.")
        } else if (paused) {
            if (resume()) notifyAgent?.invoke("[حالة النظام] استأنف المضيف الصوت. لا تتكلم أثناءه.")
        }
    }

    /**
     * Barge-in: true when the microphone has carried speech for about 0.35 s. Only used when the clip goes out through the wired route
     * (so the phone's own mic does not hear it); otherwise the buttons are the way to talk over a clip.
     */
    fun bargeFeed(pcm16k: ByteArray): Boolean {
        var peak = 0
        var i = 0
        while (i + 1 < pcm16k.size) {
            val v = ((pcm16k[i].toInt() and 0xFF) or (pcm16k[i + 1].toInt() shl 8)).toShort().toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            i += 2
        }
        val ms = pcm16k.size / 32
        if (peak > 2500) loudMs += ms else loudMs = (loudMs - ms).coerceAtLeast(0)
        if (loudMs >= 350) {
            loudMs = 0
            return true
        }
        return false
    }

    private class Resampler(private val inRate: Int) {
        private var carry = 0.0
        private var prev: Short = 0
        private val step = inRate.toDouble() / OUT_RATE

        fun process(s: ShortArray, n: Int): ShortArray {
            if (n == 0) return ShortArray(0)
            if (inRate == OUT_RATE) return s.copyOf(n)
            val out = ArrayList<Short>((n / step).toInt() + 2)
            var x = carry
            fun at(i: Int): Int = if (i < 0) prev.toInt() else s[minOf(i, n - 1)].toInt()
            if (step > 1.0) {
                // down-sampling: average everything between two output points (a simple low-pass that avoids harsh aliasing)
                while (x + step <= n - 1) {
                    val i0 = floor(x).toInt()
                    val i1 = floor(x + step).toInt()
                    var sum = 0L
                    var cnt = 0
                    for (i in i0..i1) { sum += at(i); cnt++ }
                    out.add((sum / cnt).toShort())
                    x += step
                }
            } else {
                while (x < n - 1) {
                    val i0 = floor(x).toInt()
                    val f = x - i0
                    out.add((at(i0) * (1 - f) + at(i0 + 1) * f).toInt().toShort())
                    x += step
                }
            }
            carry = x - n
            prev = s[n - 1]
            return out.toShortArray()
        }
    }

    /** Starts playback; false when the file has no decodable audio. onEnd(true) when it finished by itself. */
    fun play(
        player: AgentPlayer, f: File, fromSec: Int, name: String, sourceId: String, secs: Int, outline: String,
        onEnd: (Boolean) -> Unit
    ): Boolean {
        val probe = MediaExtractor()
        var track = -1
        try {
            probe.setDataSource(f.path)
            for (i in 0 until probe.trackCount) {
                if ((probe.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
            }
        } catch (e: Exception) {
            AppLog.add("تشغيل صوت: ملف غير مدعوم (${e.javaClass.simpleName})")
        } finally {
            try { probe.release() } catch (e: Exception) { }
        }
        if (track < 0) return false

        halt()
        paused = false
        cur = Resume(player, f, name, sourceId, secs, outline, onEnd, fromSec)
        val my = ++gen
        pl = player
        title = name
        srcId = sourceId
        durationSecs = secs
        sections = parseSections(outline)
        startSec = fromSec
        fed = 0
        playing = true
        fire()
        Thread { decode(my, player, f, track, fromSec, onEnd) }.also { it.isDaemon = true }.start()
        return true
    }

    private fun decode(my: Int, p: AgentPlayer, f: File, track: Int, fromSec: Int, onEnd: (Boolean) -> Unit) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var finished = false
        try {
            ex.setDataSource(f.path)
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            if (fromSec > 0) ex.seekTo(fromSec * 1_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var rs = Resampler(rate)
            val info = MediaCodec.BufferInfo()
            val acc = ShortArray(CHUNK)
            var accN = 0
            var inDone = false
            var outDone = false

            fun push(n: Int) {
                val b = ByteArray(n * 2)
                for (i in 0 until n) {
                    b[i * 2] = (acc[i].toInt() and 0xFF).toByte()
                    b[i * 2 + 1] = ((acc[i].toInt() shr 8) and 0xFF).toByte()
                }
                while (my == gen && p.queued() > 25) Thread.sleep(40) // keeps the queue short so "stop" reacts within seconds
                if (my != gen) return
                p.enqueue(b)
                fed += n
            }

            while (!outDone && my == gen) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib: ByteBuffer = c.getInputBuffer(ii) ?: continue
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rs = Resampler(rate)
                } else if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val n = sb.remaining()
                        val sh = ShortArray(n)
                        sb.get(sh)
                        val frames = n / max(1, ch)
                        val mono = ShortArray(frames)
                        for (i in 0 until frames) {
                            var sum = 0
                            for (k in 0 until ch) sum += sh[i * ch + k]
                            mono[i] = (sum / ch).toShort()
                        }
                        val out = rs.process(mono, frames)
                        for (v in out) {
                            acc[accN++] = v
                            if (accN == CHUNK) {
                                push(CHUNK)
                                accN = 0
                            }
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            if (my == gen && accN > 0) push(accN)
            while (my == gen && p.queued() > 0) Thread.sleep(50)
            if (my == gen) {
                Thread.sleep(700) // the last block is still inside the audio buffer
                finished = my == gen
            }
        } catch (e: Exception) {
            AppLog.add("تشغيل صوت: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { ex.release() } catch (e: Exception) { }
            if (my == gen) {
                playing = false
                if (finished) cur = null
                fire()
            }
        }
        if (finished) onEnd(true)
    }
}
