package app.dayf

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs
import app.dayf.Ui.put

/** Talk to the agent through the phone's own microphone to tune persona, rules and voice. */
class StudioActivity : Activity(), LiveClient.Listener {
    private lateinit var transcript: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var power: PowerButton
    private lateinit var wave: WaveView
    private lateinit var status: TextView

    private var live: LiveClient? = null
    private var player: AgentPlayer? = null
    private var voice: VoiceOut? = null
    private var rec: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var halfDuplex = true
    private var supa: Supa? = null
    private var cfg: Cfg? = null
    private var curUser: TextView? = null
    private var curAgent: TextView? = null
    private val reqMic = 51

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppLog.installCrashHandler(this)
        window.setBackgroundDrawable(ArabesqueBg(this))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(18), dp(40), dp(18), dp(20))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val back = IconView(this, Ic.BACK, Ui.GOLD_L)
        back.isClickable = true
        back.setOnClickListener { finish() }
        head.addView(back, LinearLayout.LayoutParams(dp(30), dp(30)))
        head.addView(Ui.title(this, "استوديو المحادثة", "تحدّث معه بمايك الهاتف مباشرة"), Ui.lp(this, 0, Ui.WRAP, 1f, start = 10))
        root.put(head)
        root.put(Ornament(this), top = 8, h = dp(16))

        val c = Repo.cfg()
        val info = if (c.voiceProvider == "elevenlabs") "ElevenLabs • ${if (c.elevenActive == 2) c.elevenName2 else c.elevenName1}" else "Gemini • ${c.voice}"
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.addView(Ui.chip(this, c.name, Ui.GOLD_L))
        chips.addView(Ui.chip(this, info, Ui.BLUE), Ui.lp(this, Ui.WRAP, Ui.WRAP, start = 8))
        chips.addView(Ui.chip(this, c.dialect, Ui.GREEN), Ui.lp(this, Ui.WRAP, Ui.WRAP, start = 8))
        root.put(chips, top = 4)

        transcript = LinearLayout(this)
        transcript.orientation = LinearLayout.VERTICAL
        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(transcript)
        root.addView(scroll, Ui.lp(this, Ui.MATCH, 0, 1f, top = 12))
        addNote("اضغط الزر وابدأ الكلام. هنا لا توجد بوابة نداء: يرد على كل ما تقوله.")

        wave = WaveView(this)
        root.put(wave, top = 8, h = dp(48))
        status = Ui.text(this, "جاهز", 13f, Ui.MUTED)
        status.gravity = Gravity.CENTER
        root.put(status, top = 4)
        power = PowerButton(this)
        power.setOnClickListener { if (running) stopSession() else startSession() }
        val pr = LinearLayout(this)
        pr.gravity = Gravity.CENTER_HORIZONTAL
        pr.addView(power, LinearLayout.LayoutParams(dp(150), dp(150)))
        root.put(pr, top = 6)
        setContentView(root)
    }

    // ------------------------------------------------------------ bubbles
    private fun bubble(isUser: Boolean): TextView {
        val t = Ui.text(this, "", 14f, if (isUser) Ui.GOLD_L else Ui.TEXT)
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        t.background = if (isUser) Ui.shape(this, Color.TRANSPARENT, 16, Ui.GOLD_D, 1) else Ui.shape(this, Ui.CARD2, 16, Ui.LINE)
        val p = Ui.lp(this, Ui.WRAP, Ui.WRAP, top = 8)
        p.gravity = if (isUser) Gravity.START else Gravity.END
        transcript.addView(t, p)
        return t
    }

    private fun addNote(s: String) {
        transcript.addView(Ui.text(this, s, 12f, Ui.MUTED), Ui.lp(this, Ui.MATCH, Ui.WRAP, top = 6))
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ session
    private fun startSession() {
        if (AgentService.running) {
            status.text = "أوقف الوكيل أولاً"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqMic)
            return
        }
        val key = Prefs.get(this, "geminiKey")
        if (key.isBlank()) {
            status.text = "أدخل مفتاح Gemini في الإعدادات"
            return
        }
        running = true
        power.running = true
        wave.active = true
        status.text = "جارٍ التجهيز…"
        Thread {
            try {
                val s = Repo.supa(this)
                supa = s
                val raw = if (Repo.raw.length() > 0) Repo.raw else s?.config() ?: JSONObject()
                val c = Cfg(raw)
                cfg = c
                val rules = s?.rules() ?: emptyList()
                val knowledge = s?.knowledge() ?: emptyList()
                val host = Prefs.get(this, "host").ifBlank { "default" }
                val people = s?.notes(host) ?: emptyList()
                val model = c.model.ifBlank { Repo.liveModel.ifBlank { LiveClient.pickModel(key) ?: "" } }
                if (model.isBlank()) {
                    fail("لا يوجد نموذج Live لمفتاحك")
                    return@Thread
                }
                Repo.liveModel = model
                val p = AgentPlayer(this)
                p.applyGain(c.outGain.toFloat())
                p.start(false, false)
                player = p
                halfDuplex = !p.hasWired()
                val v = VoiceOut(p)
                v.onError = { m -> runOnUiThread { addNote(m) } }
                v.configure(c, Prefs.get(this, "elevenKey"))
                v.start()
                voice = v
                val sys = Prompt.system(c, rules, knowledge, people, emptyList())
                val client = LiveClient(key, this)
                live = client
                client.connect(Prompt.setup(model, c, sys, null))
            } catch (e: Exception) {
                fail("خطأ: ${e.message}")
            }
        }.start()
    }

    private fun fail(m: String) {
        AppLog.add("استوديو: $m")
        runOnUiThread {
            addNote("⚠ $m")
            stopSession()
        }
    }

    private fun stopSession() {
        running = false
        power.running = false
        wave.active = false
        StateBus.captureLevel = 0
        StateBus.speaking = false
        try { live?.close() } catch (e: Exception) { }
        try { rec?.stop() } catch (e: Exception) { }
        try { rec?.release() } catch (e: Exception) { }
        try { voice?.stop() } catch (e: Exception) { }
        try { player?.stop() } catch (e: Exception) { }
        live = null
        rec = null
        voice = null
        player = null
        status.text = "متوقف"
    }

    @Suppress("MissingPermission")
    private fun startMic() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            fail("تعذّر تشغيل الميكروفون")
            return
        }
        rec = r
        r.startRecording()
        Thread {
            val buf = ShortArray(640)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                var peak = 0
                val out = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    val a = abs(v)
                    if (a > peak) peak = a
                    out[i * 2] = (v and 0xFF).toByte()
                    out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                StateBus.captureLevel = peak * 100 / 32768
                val speaking = System.currentTimeMillis() - (player?.lastEnqueueAt ?: 0L) < 600
                StateBus.speaking = speaking
                if (!(halfDuplex && speaking)) live?.sendAudio(out)
            }
        }.start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == reqMic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSession()
    }

    // ------------------------------------------------------------ live callbacks
    override fun onSetupComplete() {
        runOnUiThread { status.text = if (halfDuplex) "تحدّث الآن (دون سماعة: يصمت المايك أثناء رده)" else "تحدّث الآن" }
        startMic()
    }

    override fun onAudio(pcm24k: ByteArray) {
        voice?.audio(pcm24k)
    }

    override fun onInputText(t: String) {
        runOnUiThread {
            curAgent = null
            val b = curUser ?: bubble(true).also { curUser = it }
            b.text = b.text.toString() + t
            scrollDown()
        }
    }

    override fun onOutputText(t: String) {
        voice?.text(t)
        runOnUiThread {
            curUser = null
            val b = curAgent ?: bubble(false).also { curAgent = it }
            b.text = b.text.toString() + t
            scrollDown()
        }
    }

    override fun onTurnComplete() {
        voice?.endTurn()
        runOnUiThread {
            curUser = null
            curAgent = null
        }
    }

    override fun onInterrupted() {
        voice?.cancel()
        runOnUiThread {
            curAgent = null
        }
    }

    override fun onToolCall(id: String, name: String, args: JSONObject) {
        Thread {
            val res = Tools.exec(supa, cfg, Prefs.get(this, "host").ifBlank { "default" }, name, args)
            live?.sendToolResponse(id, name, res)
        }.start()
    }

    override fun onResumeHandle(h: String) {}
    override fun onGoAway() {}

    override fun onClosed(code: Int, reason: String) {
        if (running) fail("انقطع الاتصال ($code) ${reason.take(120)}")
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }
}
