package app.dayf

import android.content.Context
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Routes the agent's voice. "gemini": play Gemini's own audio.
 * "elevenlabs": ignore Gemini's audio, speak the transcript sentence-by-sentence with ElevenLabs (PCM 24 kHz).
 */
class VoiceOut(private val player: AgentPlayer) {
    @Volatile var provider = "gemini"
    private var key = ""
    private var voiceId = ""
    private var model = "eleven_flash_v2_5"
    private var stability = 0.45
    private var similarity = 0.8
    var onError: ((String) -> Unit)? = null

    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val q = LinkedBlockingQueue<Pair<Int, String>>()
    @Volatile private var gen = 0
    @Volatile private var call: Call? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private val buf = StringBuilder()

    fun configure(c: Cfg, elevenKey: String) {
        val id = if (c.elevenActive == 2) c.elevenVoice2 else c.elevenVoice1
        model = c.elevenModel
        stability = c.elevenStability
        similarity = c.elevenSimilarity
        key = elevenKey
        voiceId = id
        provider = if (c.voiceProvider == "elevenlabs" && elevenKey.isNotBlank() && id.isNotBlank()) "elevenlabs" else "gemini"
        if (c.voiceProvider == "elevenlabs" && provider == "gemini") {
            onError?.invoke("ElevenLabs: المفتاح أو معرّف الصوت ناقص، سيُستعمل صوت Gemini")
        }
    }

    fun configureDirect(elevenKey: String, id: String, mdl: String, stab: Double, sim: Double) {
        key = elevenKey
        voiceId = id
        model = mdl
        stability = stab
        similarity = sim
        provider = "elevenlabs"
    }

    fun start() {
        if (running) return
        running = true
        worker = Thread {
            while (running) {
                val item = try {
                    q.take()
                } catch (e: InterruptedException) {
                    break
                }
                if (item.first == gen) synth(item.first, item.second)
            }
        }.also { it.start() }
    }

    fun audio(pcm: ByteArray) {
        if (provider != "elevenlabs") player.enqueue(pcm)
    }

    fun text(t: String) {
        if (provider != "elevenlabs") return
        synchronized(buf) {
            buf.append(t)
            drain(false)
        }
    }

    fun endTurn() {
        if (provider != "elevenlabs") return
        synchronized(buf) { drain(true) }
    }

    /** Speak a complete sentence right now (used by previews). */
    fun speak(t: String) {
        q.offer(gen to t)
    }

    fun cancel() {
        gen++
        q.clear()
        synchronized(buf) { buf.setLength(0) }
        try {
            call?.cancel()
        } catch (e: Exception) {
        }
        player.flush()
    }

    fun stop() {
        running = false
        worker?.interrupt()
        try {
            call?.cancel()
        } catch (e: Exception) {
        }
    }

    private fun drain(force: Boolean) {
        while (true) {
            val s = buf.toString()
            var cut = -1
            if (s.length >= 24) {
                for (i in 23 until s.length) {
                    if (s[i] in ".!?؟\n،,:؛…") {
                        cut = i + 1
                        break
                    }
                }
            }
            if (cut < 0 && s.length > 140) {
                val sp = s.lastIndexOf(' ', 140)
                cut = if (sp > 40) sp + 1 else 140
            }
            if (cut < 0) break
            emit(s.substring(0, cut))
            buf.delete(0, cut)
        }
        if (force) {
            val rest = buf.toString()
            buf.setLength(0)
            emit(rest)
        }
    }

    private fun emit(t: String) {
        val x = t.trim()
        if (x.isNotEmpty()) q.offer(gen to x)
    }

    private fun synth(g: Int, text: String) {
        try {
            val body = JSONObject()
                .put("text", text)
                .put("model_id", model)
                .put("voice_settings", JSONObject().put("stability", stability).put("similarity_boost", similarity))
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=pcm_24000")
                .header("xi-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val c = http.newCall(req)
            call = c
            c.execute().use { r ->
                if (!r.isSuccessful) {
                    onError?.invoke("ElevenLabs HTTP ${r.code}: ${r.body?.string()?.take(160)}")
                    return
                }
                val ins = r.body?.byteStream() ?: return
                val chunk = ByteArray(4800)
                var left = ByteArray(0)
                while (g == gen) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    val data = left + chunk.copyOf(n)
                    val even = data.size and -2
                    if (even > 0) player.enqueue(data.copyOf(even))
                    left = data.copyOfRange(even, data.size)
                }
            }
        } catch (e: Exception) {
            if (g == gen) onError?.invoke("ElevenLabs: ${e.message}")
        }
    }
}

/** Plays a short sample of a voice so it can be chosen by ear. */
object VoicePreview {
    private var live: LiveClient? = null
    private var out: VoiceOut? = null
    private var player: AgentPlayer? = null

    fun stop() {
        try { live?.close() } catch (e: Exception) { }
        try { out?.stop() } catch (e: Exception) { }
        try { player?.stop() } catch (e: Exception) { }
        live = null
        out = null
        player = null
    }

    fun gemini(ctx: Context, apiKey: String, voiceName: String, onState: (String) -> Unit) {
        stop()
        Thread {
            try {
                val model = Repo.liveModel.ifBlank { LiveClient.pickModel(apiKey) ?: "" }
                if (model.isBlank()) {
                    onState("لا يوجد نموذج Live لمفتاحك")
                    return@Thread
                }
                Repo.liveModel = model
                val p = AgentPlayer(ctx)
                p.start(false, false)
                player = p
                val cfg = Cfg(JSONObject().put("voice", voiceName).put("temperature", 0.6))
                val sys = "أنت مذيع. عندما تصلك رسالة نصية فانطقها كما هي حرفياً بصوتك الطبيعي ودون أي زيادة."
                val client = LiveClient(apiKey, object : LiveClient.Listener {
                    override fun onSetupComplete() {
                        onState("يتكلم…")
                        live?.sendText(Catalog.SAMPLE)
                    }
                    override fun onAudio(pcm24k: ByteArray) { p.enqueue(pcm24k) }
                    override fun onInputText(t: String) {}
                    override fun onOutputText(t: String) {}
                    override fun onTurnComplete() {
                        onState("")
                        Thread {
                            Thread.sleep(2500)
                            if (player === p) stop()
                        }.start()
                    }
                    override fun onInterrupted() {}
                    override fun onToolCall(id: String, name: String, args: JSONObject) {}
                    override fun onResumeHandle(h: String) {}
                    override fun onGoAway() {}
                    override fun onClosed(code: Int, reason: String) {
                        if (code != 1000) onState("تعذّرت المعاينة ($code) ${reason.take(80)}")
                    }
                })
                live = client
                client.connect(Prompt.setup(model, cfg, sys, null))
            } catch (e: Exception) {
                onState("خطأ: ${e.message}")
            }
        }.start()
    }

    fun eleven(ctx: Context, key: String, id: String, model: String, stab: Double, sim: Double, onState: (String) -> Unit) {
        stop()
        val p = AgentPlayer(ctx)
        p.start(false, false)
        player = p
        val v = VoiceOut(p)
        v.configureDirect(key, id, model, stab, sim)
        v.onError = { onState(it) }
        v.start()
        out = v
        onState("يتكلم…")
        v.speak(Catalog.SAMPLE)
        Thread {
            Thread.sleep(6000)
            if (player === p) {
                stop()
                onState("")
            }
        }.start()
    }
}
