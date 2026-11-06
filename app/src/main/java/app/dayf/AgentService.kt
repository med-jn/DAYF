package app.dayf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AgentService : Service(), LiveClient.Listener, Overlay.Actions {

    companion object {
        const val ACTION_START = "app.dayf.START"
        const val ACTION_STOP = "app.dayf.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DIAG = "diag"
        private const val CH = "dayf"
        private const val NOTIF_ID = 7
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var capture: PlaybackCapture? = null
    private var player: AgentPlayer? = null
    private var grabber: ScreenGrabber? = null
    private var overlay: Overlay? = null
    private var live: LiveClient? = null
    private var supa: Supa? = null
    private var cfg: Cfg? = null
    private var wake: PowerManager.WakeLock? = null

    @Volatile private var stopping = false
    private var diag = false
    private var gemKey = ""
    private var model = ""
    private var host = "default"
    private var route = ""
    private var resumeHandle: String? = null
    private var attempt = 0
    private var lastNote = ""
    private val liveId = UUID.randomUUID().toString()

    private var rules: List<String> = emptyList()
    private var knowledge: List<Pair<String, String>> = emptyList()
    private var people: List<Pair<String, String>> = emptyList()
    private var prevLines: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private val recent = java.util.ArrayDeque<String>()

    // turn state
    private var turnDecided = false
    private var turnAllowed = false
    private var spokenThisTurn = false
    @Volatile private var lastAudioAt = 0L
    private val inBuf = StringBuilder()
    private val outBuf = StringBuilder()
    private val textLock = Any()
    private var lastFlush = 0L

    // vision hysteresis
    private val visionBusy = AtomicBoolean(false)
    private var visPrevG: GuestState? = null
    private var visPrevM: MicState? = null

    private var lastAnnG: GuestState? = null
    private var lastAnnM: MicState? = null
    private var introduced = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val act = intent?.action
        if (act == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent != null && act == ACTION_START) {
            if (running) return START_NOT_STICKY
            running = true
            diag = intent.getBooleanExtra(EXTRA_DIAG, false)
            startForeground(NOTIF_ID, notif("جارٍ التشغيل…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dayf:agent").also { it.acquire(6 * 60 * 60 * 1000L) }
            try {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data!!)
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post { shutdown() }
                    }
                }, main)
                projection = p
            } catch (e: Exception) {
                fail("تعذّر بدء مشاركة الشاشة: ${e.message}")
                return START_NOT_STICKY
            }
            Thread { boot() }.start()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun notif(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "الضيف", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("الضيف يعمل")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stop)
            .setOngoing(true)
            .build()
    }

    private fun fail(msg: String) {
        main.post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            shutdown()
        }
    }

    // ---------------------------------------------------------------- boot

    private fun boot() {
        try {
            if (!Settings.canDrawOverlays(this)) return fail("فعّل «الظهور فوق التطبيقات» من شاشة التطبيق الرئيسية.")
            var commands: List<String> = emptyList()
            if (!diag) {
                gemKey = Prefs.get(this, "geminiKey")
                val su = Prefs.get(this, "supaUrl")
                val sk = Prefs.get(this, "supaKey")
                if (gemKey.isBlank() || su.isBlank() || sk.isBlank()) return fail("أدخل مفتاح Gemini وبيانات Supabase أولاً.")
                val s = Supa(su, sk)
                supa = s
                val raw = s.config()
                if (raw != null) Prefs.put(this, "cfgCache", raw.toString())
                val use = raw
                    ?: Prefs.get(this, "cfgCache").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                    ?: return fail("تعذّر قراءة الإعدادات من Supabase: ${s.lastError}")
                val c = Cfg(use)
                cfg = c
                rules = s.rules()
                knowledge = s.knowledge()
                host = Prefs.get(this, "host").ifBlank { "default" }
                people = s.notes(host)
                prevLines = s.recentTranscripts(host, 20)
                names = (listOf(c.name) + c.aliases.split(',', '،', '\n')).map { Ar.norm(it) }.filter { it.isNotBlank() }
                model = c.model.ifBlank { LiveClient.pickModel(gemKey) ?: "" }
                if (model.isBlank()) return fail("لم أجد نموذج Live متاحاً لمفتاحك.")
                StateBus.mode = try {
                    SpeakMode.valueOf(c.defaultMode)
                } catch (e: Exception) {
                    SpeakMode.ON_CALL
                }
                StateBus.allowUnknown = c.allowUnknown
                TikTokWatcher.keywords = Keywords.from(c.keywords)
                commands = c.quickCommands
                s.startSession(liveId, host)
            }
            main.post { startUi(commands) }

            if (!diag) {
                val p = AgentPlayer(this)
                p.applyGain(cfg?.outGain?.toFloat() ?: 1f)
                route = p.start()
                player = p
            }
            val proj = projection ?: return
            val cap = PlaybackCapture(proj) { pcm -> onCapture(pcm) }
            val err = cap.start()
            if (err != null) lastNote = err
            capture = cap

            if (!diag) {
                val g = ScreenGrabber(this, proj, (cfg?.frameIntervalMs ?: 2000).toLong()) { jpg ->
                    if (StateBus.shouldListen()) live?.sendVideo(jpg)
                }
                g.start()
                grabber = g
                connectLive()
                main.postDelayed(visionTick, 4000)
            }
        } catch (e: Exception) {
            fail("خطأ أثناء التشغيل: ${e.message}")
        }
    }

    private fun startUi(commands: List<String>) {
        val o = Overlay(this, this, commands)
        o.show()
        overlay = o
        StateBus.listeners.add(stateListener)
        main.post(ticker)
    }

    // ---------------------------------------------------------------- live

    private fun connectLive() {
        if (stopping) return
        val c = cfg ?: return
        val recap = if (resumeHandle == null) (prevLines + recent.toList()).takeLast(40) else emptyList()
        val sys = Prompt.system(c, rules, knowledge, people, recap)
        val client = LiveClient(gemKey, this)
        live = client
        client.connect(Prompt.setup(model, c, sys, resumeHandle))
    }

    private fun onCapture(pcm: ByteArray) {
        if (diag) return
        if (!StateBus.shouldListen()) return
        live?.sendAudio(pcm)
    }

    override fun onSetupComplete() {
        attempt = 0
        lastAnnG = null
        main.post { announce.run() }
    }

    override fun onAudio(pcm24k: ByteArray) {
        val now = System.currentTimeMillis()
        if (!turnDecided) {
            turnDecided = true
            val why = StateBus.canStartTurn(now)
            turnAllowed = why == null
            if (why != null) StateBus.lastBlock = why
        } else if (turnAllowed) {
            val why = StateBus.hardBlock(now)
            if (why != null) {
                turnAllowed = false
                StateBus.lastBlock = why
                player?.flush()
            }
        }
        if (turnAllowed) {
            spokenThisTurn = true
            lastAudioAt = now
            player?.enqueue(pcm24k)
        }
    }

    override fun onInputText(t: String) {
        synchronized(textLock) {
            inBuf.append(t)
            val tail = inBuf.takeLast(80).toString()
            if (Ar.mentions(tail, names)) {
                val w = (cfg?.addressWindowS ?: 25) * 1000L
                StateBus.addressedUntil = System.currentTimeMillis() + w
            }
            if (inBuf.length > 800) flushLocked()
        }
    }

    override fun onOutputText(t: String) {
        synchronized(textLock) { outBuf.append(t) }
    }

    override fun onTurnComplete() {
        if (turnAllowed && spokenThisTurn) {
            val f = (cfg?.followupWindowS ?: 20) * 1000L
            StateBus.addressedUntil = maxOf(StateBus.addressedUntil, System.currentTimeMillis() + f)
        }
        synchronized(textLock) { flushLocked() }
        turnDecided = false
        turnAllowed = false
        spokenThisTurn = false
    }

    override fun onInterrupted() {
        player?.flush()
        turnDecided = false
        turnAllowed = false
        spokenThisTurn = false
    }

    private fun flushLocked() {
        lastFlush = System.currentTimeMillis()
        val i = inBuf.toString().trim()
        val o = outBuf.toString().trim()
        inBuf.setLength(0)
        outBuf.setLength(0)
        if (i.isNotEmpty()) remember("other", i)
        if (o.isNotEmpty() && spokenThisTurn) remember("agent", o)
    }

    private fun remember(role: String, text: String) {
        recent.addLast((if (role == "agent") "أنت: " else "الحاضرون: ") + text)
        while (recent.size > 40) recent.removeFirst()
        val s = supa ?: return
        try {
            exec.execute { s.addTranscript(liveId, host, role, text) }
        } catch (e: Exception) {
        }
    }

    override fun onToolCall(id: String, name: String, args: JSONObject) {
        Thread {
            val res = JSONObject()
            val s = supa
            val c = cfg
            try {
                if (s == null || c == null) {
                    res.put("error", "no database")
                } else when (name) {
                    "lookup_knowledge" -> {
                        val rows = s.search(args.optString("query"))
                        if (rows.isEmpty()) {
                            res.put("found", false).put("message", c.unknownReply)
                        } else {
                            val arr = JSONArray()
                            rows.forEach { arr.put(JSONObject().put("topic", it.first).put("content", it.second)) }
                            res.put("found", true).put("items", arr)
                        }
                    }
                    "remember_person" -> {
                        res.put("saved", s.addNote(host, args.optString("person"), args.optString("note")))
                    }
                    "recall_person" -> {
                        val rows = s.notes(host, args.optString("person"), 15)
                        val arr = JSONArray()
                        rows.forEach { arr.put(it.second) }
                        res.put("notes", arr)
                    }
                    "report_unknown" -> {
                        s.addUnanswered(host, args.optString("question"))
                        res.put("saved", true)
                    }
                    else -> res.put("error", "unknown tool")
                }
            } catch (e: Exception) {
                res.put("error", e.message ?: "error")
            }
            live?.sendToolResponse(id, name, res)
        }.start()
    }

    override fun onResumeHandle(h: String) {
        resumeHandle = h
    }

    override fun onGoAway() {
        lastNote = "الخادم سيعيد الاتصال قريباً"
    }

    override fun onClosed(code: Int, reason: String) {
        if (stopping) return
        lastNote = "انقطع Gemini ($code) ${reason.take(80)}"
        attempt++
        if (attempt > 10) {
            fail("تعذّر الاتصال بـ Gemini بعد عدة محاولات: $reason")
            return
        }
        if (attempt > 3) resumeHandle = null
        main.postDelayed({ connectLive() }, minOf(1000L * attempt, 8000L))
    }

    // ---------------------------------------------------------------- state

    private val stateListener: () -> Unit = {
        main.removeCallbacks(announce)
        main.postDelayed(announce, 1200)
        updateOverlay()
    }

    private val announce = Runnable {
        val g = StateBus.guestEff
        val m = StateBus.micEff
        if (g == lastAnnG && m == lastAnnM) return@Runnable
        lastAnnG = g
        lastAnnM = m
        val text = when {
            g == GuestState.GUEST && !introduced && m != MicState.MUTED -> {
                introduced = true
                StateBus.forceUntil = System.currentTimeMillis() + 25000
                "[حالة النظام] قُبلت ضيفاً في البث والمايك مفتوح. عرّف بنفسك الآن بجملة قصيرة وفق قاعدة الإفصاح ثم اصمت."
            }
            g == GuestState.GUEST && m == MicState.MUTED -> "[حالة النظام] أنت ضيف لكن المايك مغلق: لا أحد يسمعك. لا تتكلم."
            g == GuestState.GUEST -> "[حالة النظام] أنت ضيف في البث والمايك مفتوح."
            g == GuestState.REMOVED -> "[حالة النظام] أُنزلت من البث أو غادرته. لا تتكلم أبداً حتى يُعاد قبولك."
            g == GuestState.REQUESTED -> "[حالة النظام] أرسلت طلب الانضمام وما زلت بانتظار قبول المضيف. لا تتكلم."
            g == GuestState.INVITED -> "[حالة النظام] وصلتك دعوة ولم تُقبل بعد. لا تتكلم."
            g == GuestState.VIEWER -> "[حالة النظام] أنت مشاهد فقط وليس لك صوت في البث. لا تتكلم."
            else -> "[حالة النظام] حالتك الآن: ${g.ar}. لا تتكلم."
        }
        live?.sendText(text)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (stopping) return
            val now = System.currentTimeMillis()
            StateBus.speaking = now - lastAudioAt < 700
            updateOverlay()
            if (now - lastFlush > 15000) synchronized(textLock) { flushLocked() }
            main.postDelayed(this, 700)
        }
    }

    private fun updateOverlay() {
        val o = overlay ?: return
        val now = System.currentTimeMillis()
        val hard = StateBus.hardBlock(now)
        val connected = diag || live?.ready == true
        val color = when {
            !connected -> Color.GRAY
            StateBus.speaking -> Color.parseColor("#1E88E5")
            hard == null -> Color.parseColor("#2E7D32")
            else -> Color.parseColor("#C62828")
        }
        val bars = "▮".repeat((StateBus.captureLevel / 12).coerceIn(0, 8))
        val gate = if (diag) "وضع الاختبار (بلا Gemini)" else if (hard == null) {
            if (StateBus.mode == SpeakMode.ON_CALL && now > StateBus.addressedUntil && now > StateBus.forceUntil) "جاهز، ينتظر النداء" else "مسموح له بالكلام"
        } else "ممنوع: $hard"
        val txt = "الحالة: ${StateBus.guestEff.ar} | المايك: ${StateBus.micEff.ar}\n" +
            "الوضع: ${StateBus.mode.ar} | البوابة: $gate\n" +
            "التقاط الصوت: $bars ${StateBus.captureLevel}%\n" +
            "Gemini: ${if (connected) "متصل" else "غير متصل"} | الإخراج: $route" +
            (if (lastNote.isNotEmpty()) "\n$lastNote" else "")
        val gl = "ضيف: " + (StateBus.manualGuest?.ar ?: "تلقائي")
        val ml = "مايك: " + (StateBus.manualMic?.ar ?: "تلقائي")
        o.update(txt, color, gl, ml)
    }

    // ---------------------------------------------------------------- vision

    private val visionTick = object : Runnable {
        override fun run() {
            if (stopping) return
            val jpg = grabber?.latest
            if (jpg != null && visionBusy.compareAndSet(false, true)) {
                exec.execute {
                    try {
                        runVision(jpg)
                    } finally {
                        visionBusy.set(false)
                    }
                }
            }
            main.postDelayed(this, (cfg?.visionIntervalS ?: 6) * 1000L)
        }
    }

    private fun runVision(jpg: ByteArray) {
        val c = cfg ?: return
        val me = Prefs.get(this, "myName").ifBlank { "the user" }
        val prompt = c.visionPrompt.ifBlank { Vision.DEFAULT_PROMPT }.replace("{me}", me)
        val j = Vision.classify(gemKey, c.visionModel, prompt, jpg) ?: return
        val g = when (j.optString("role")) {
            "viewer" -> GuestState.VIEWER
            "requested" -> GuestState.REQUESTED
            "invited" -> GuestState.INVITED
            "guest" -> GuestState.GUEST
            "not_live" -> GuestState.NOT_LIVE
            else -> GuestState.UNKNOWN
        }
        val m = when (j.optString("my_mic")) {
            "open" -> MicState.OPEN
            "muted" -> MicState.MUTED
            else -> MicState.UNKNOWN
        }
        lastNote = "الرؤية: " + j.optString("evidence")
        if (g == visPrevG && m == visPrevM) {
            StateBus.visGuest = g
            StateBus.visMic = m
            StateBus.recompute()
        }
        visPrevG = g
        visPrevM = m
    }

    // ---------------------------------------------------------------- panel actions

    override fun onStopSpeaking() {
        player?.flush()
        StateBus.silenceUntil = System.currentTimeMillis() + 30000
        turnAllowed = false
    }

    override fun onMode(m: SpeakMode) {
        StateBus.mode = m
        if (m == SpeakMode.SILENT) player?.flush()
        updateOverlay()
    }

    override fun onCycleGuest() {
        StateBus.manualGuest = when (StateBus.manualGuest) {
            null -> GuestState.GUEST
            GuestState.GUEST -> GuestState.VIEWER
            else -> null
        }
        StateBus.recompute()
        updateOverlay()
    }

    override fun onCycleMic() {
        StateBus.manualMic = when (StateBus.manualMic) {
            null -> MicState.OPEN
            MicState.OPEN -> MicState.MUTED
            else -> null
        }
        StateBus.recompute()
        updateOverlay()
    }

    override fun onCommand(text: String) {
        val now = System.currentTimeMillis()
        StateBus.silenceUntil = 0
        StateBus.forceUntil = now + 25000
        StateBus.addressedUntil = now + 25000
        live?.sendText("[تعليمات المخرج] $text")
    }

    override fun onVolume(delta: Float) {
        val p = player ?: return
        p.applyGain(p.gain + delta)
        lastNote = "مستوى الصوت: ${(p.gain * 100).toInt()}%"
    }

    override fun onDump() {
        val sb = StringBuilder()
        sb.append("guestEff=").append(StateBus.guestEff).append(" micEff=").append(StateBus.micEff)
            .append(" a11y=").append(StateBus.a11yGuest).append('/').append(StateBus.a11yMic)
            .append(" vision=").append(StateBus.visGuest).append('/').append(StateBus.visMic)
            .append(" note=").append(lastNote).append('\n')
        sb.append(TikTokWatcher.dump())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dump", sb.toString().take(30000)))
        Toast.makeText(this, "نُسخت بنية الشاشة. الصقها في المحادثة.", Toast.LENGTH_LONG).show()
    }

    override fun onExit() {
        shutdown()
    }

    // ---------------------------------------------------------------- shutdown

    private fun shutdown() {
        if (stopping) return
        stopping = true
        running = false
        main.removeCallbacksAndMessages(null)
        StateBus.listeners.remove(stateListener)
        try { live?.close() } catch (e: Exception) { }
        try { capture?.stop() } catch (e: Exception) { }
        try { grabber?.stop() } catch (e: Exception) { }
        try { player?.stop() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        try { overlay?.hide() } catch (e: Exception) { }
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        exec.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
