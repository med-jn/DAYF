package app.dayf

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs

/** Captures the phone's internal audio (what other apps play). Our own app's audio is excluded. */
class PlaybackCapture(
    private val projection: MediaProjection,
    private val onPcm16k: (ByteArray) -> Unit
) {
    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): String? {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()
        var factor = 3
        var r = build(cfg, 48000)
        if (r == null) {
            factor = 1
            r = build(cfg, 16000)
        }
        if (r == null) return "تعذّر إنشاء التقاط الصوت الداخلي"
        rec = r
        running = true
        r.startRecording()
        val f = factor
        thread = Thread { loop(r, f) }.also { it.start() }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun build(cfg: AudioPlaybackCaptureConfiguration, rate: Int): AudioRecord? {
        return try {
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (a.state == AudioRecord.STATE_INITIALIZED) a else {
                a.release()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loop(r: AudioRecord, factor: Int) {
        val n = 640 * factor // 40 ms of audio
        val buf = ShortArray(n)
        while (running) {
            val got = r.read(buf, 0, n)
            if (got < 0) break
            if (got == 0) continue
            val outLen = got / factor
            val out = ByteArray(outLen * 2)
            var peak = 0
            for (i in 0 until outLen) {
                var s = 0
                for (k in 0 until factor) s += buf[i * factor + k].toInt()
                val v = s / factor
                val a = abs(v)
                if (a > peak) peak = a
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            StateBus.captureLevel = peak * 100 / 32768
            onPcm16k(out)
        }
    }

    fun stop() {
        running = false
        try {
            rec?.stop()
        } catch (e: Exception) {
        }
        try {
            rec?.release()
        } catch (e: Exception) {
        }
        rec = null
    }
}

/** Plays the agent's voice (24 kHz PCM). Prefers the wired headset jack so the sound loops into the mic. */
class AgentPlayer(private val ctx: Context) {
    private var track: AudioTrack? = null
    private val q = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var gain = 1.0f

    fun start(): String {
        val rate = 24000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wired = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (wired != null) t.setPreferredDevice(wired)
        t.setVolume(gain)
        t.play()
        track = t
        running = true
        thread = Thread { loop() }.also { it.start() }
        return if (wired != null) "سماعة سلكية" else "مكبّر الهاتف (السماعة السلكية غير موصولة!)"
    }

    private fun loop() {
        while (running) {
            val b = try {
                q.take()
            } catch (e: InterruptedException) {
                break
            }
            try {
                track?.write(b, 0, b.size)
            } catch (e: Exception) {
            }
        }
    }

    fun enqueue(b: ByteArray) {
        q.offer(b)
    }

    fun setGain(g: Float) {
        gain = g.coerceIn(0.05f, 1.0f)
        try {
            track?.setVolume(gain)
        } catch (e: Exception) {
        }
    }

    fun flush() {
        q.clear()
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (e: Exception) {
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        try {
            track?.stop()
        } catch (e: Exception) {
        }
        try {
            track?.release()
        } catch (e: Exception) {
        }
        track = null
    }
}
