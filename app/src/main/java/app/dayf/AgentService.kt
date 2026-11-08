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
    private var voice: VoiceOut? = null
    private var visionModel = ""
    private var visionFallbackTried = false
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
    @Volatile private var lastVerifyAt = 0L
    private val yieldHits = java.util.ArrayDeque<Pair<Long, Int>>()
    private var inputSinceDecision = 0
    private var extrasOn = false
    private var proactiveFailed = false
    private var affectiveFailed = false
    private var proactiveOn = false
    private var affectiveOn = false
    private var setupDone = false
    private var duckUntil = 0L
    private var ducked = false
    private val verifyLock = Any()

    private var lastAnnG: GuestState? = null
    private var lastAnnM: MicState? = null
    private var introduced = false
    private var lastVisionErrAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.installCrashHandler(this)
    }

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
            AppLog.add("بدء الخدمة" + (if (diag) " (وضع اختبار الالتقاط)" else ""))
            if (Settings.canDrawOverlays(this)) {
                startUi()
            } else {
                fail("صلاحية «الظهور فوق التطبيقات» غير ممنوحة")
                return START_NOT_STICKY
            }
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
        AppLog.add("خطأ: $msg")
        main.post {
            lastNote = msg
            overlay?.setError(msg)
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, notif("خطأ: $msg"))
            } catch (e: Exception) {
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            main.postDelayed({ shutdown() }, 25000)
        }
    }

    // ---------------------------------------------------------------- boot

    private fun boot() {
        try {
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
                visionModel = c.visionModel.ifBlank { LiveClient.pickTextModel(gemKey) ?: "" }
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
                AppLog.add("Supabase ✔ الوكيل «${c.name}» | قواعد ${rules.size} | معرفة ${knowledge.size} | أسماء النداء ${names.size} | نموذج $model")
            }
            main.post { overlay?.setCommands(commands) }

            if (!diag) {
                val p = AgentPlayer(this)
                p.applyGain(cfg?.outGain?.toFloat() ?: 1f)
                route = p.start()
                player = p
                val v = VoiceOut(p)
                v.onError = { m ->
                    AppLog.add(m)
                    lastNote = m
                }
                v.configure(cfg!!, Prefs.get(this, "elevenKey"))
                v.start()
                voice = v
                AppLog.add("مزوّد الصوت: ${if (v.provider == "elevenlabs") "ElevenLabs" else "Gemini (${cfg!!.voice})"}")
            }
            val proj = projection ?: return
            val cap = PlaybackCapture(proj) { pcm -> onCapture(pcm) }
            val err = cap.start()
            if (err != null) lastNote = err
            AppLog.add(err ?: "التقاط الصوت الداخلي يعمل")
            capture = cap

            if (!diag) {
                val g = ScreenGrabber(this, proj)
                g.start()
                grabber = g
                connectLive()
                if (cfg?.screenMode == "periodic") main.postDelayed(periodicTick, 5000)
            }
        } catch (e: Exception) {
            AppLog.add(e.stackTrace.take(6).joinToString("\n") { "   at $it" })
            fail("خطأ أثناء التشغيل: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startUi() {
        val o = Overlay(this, this)
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
        setupDone = false
        proactiveOn = c.proactiveAudio && !proactiveFailed
        affectiveOn = c.affectiveDialog && !affectiveFailed
        extrasOn = proactiveOn || affectiveOn
        client.connect(
            Prompt.setup(model, c, sys, resumeHandle, proactiveOn, c.interruptPolicy == "always", affectiveOn),
            extrasOn
        )
    }

    private fun onCapture(pcm: ByteArray) {
        if (diag) return
        if (!StateBus.shouldListen()) return
        live?.sendAudio(pcm)
    }

    override fun onSetupComplete() {
        setupDone = true
        AppLog.add(
            "Gemini Live جاهز" +
                (if (proactiveOn) " • صمت ذكي" else "") +
                (if (affectiveOn) " • حوار عاطفي" else "") +
                (if (!extrasOn) " • الوضع الأساسي" else "")
        )
        if (cfg?.screenMode != "manual") exec.execute { verifyNow("بداية الجلسة", false) }
        attempt = 0
        lastAnnG = null
        main.post { announce.run() }
    }

    private fun decideTurn(now0: Long) {
        var now = now0
        if (!turnDecided && StateBus.mode != SpeakMode.SILENT && cfg?.screenMode == "on_demand" && !isFresh(now)) {
            verifyNow("قبل الكلام", false)
            now = System.currentTimeMillis()
        }
        if (!turnDecided) {
            turnDecided = true
            var why = StateBus.canStartTurn(now)
            val heard = inputSinceDecision
            inputSinceDecision = 0
            if (why == null && heard < 3 && now > StateBus.forceUntil) why = "لا كلام مفهوم (ضجيج أو موسيقى)"
            turnAllowed = why == null
            if (why != null) StateBus.lastBlock = why
        } else if (turnAllowed) {
            val why = StateBus.hardBlock(now, true)
            if (why != null) {
                turnAllowed = false
                StateBus.lastBlock = why
                voice?.cancel()
            }
        }
    }

    override fun onAudio(pcm24k: ByteArray) {
        val now = System.currentTimeMillis()
        decideTurn(now)
        if (turnAllowed) {
            spokenThisTurn = true
            lastAudioAt = now
            voice?.audio(pcm24k)
        }
    }

    private fun updateDuck(now: Long) {
        val want = now < duckUntil && StateBus.speaking
        if (want != ducked) {
            ducked = want
            player?.duck(want)
        }
    }

    /** A human eases off when someone really takes the floor, but not for every sound. */
    private fun maybeYield(t: String) {
        val c = cfg ?: return
        if (c.interruptPolicy != "smart" || !StateBus.speaking) return
        val now = System.currentTimeMillis()
        duckUntil = now + 1200
        updateDuck(now)
        yieldHits.addLast(now to t.length)
        while (yieldHits.isNotEmpty() && now - yieldHits.peekFirst()!!.first > c.yieldWindowMs) yieldHits.removeFirst()
        val total = yieldHits.sumOf { it.second }
        val stop = now < StateBus.addressedUntil && Ar.hasStopWord(t)
        val called = Ar.mentions(t, names) && total >= 12
        if (total >= c.yieldChars || stop || called) {
            yieldHits.clear()
            AppLog.add("تنازل عن الكلام: " + (if (stop) "طُلب منه التوقف" else if (called) "نودي باسمه" else "أحدهم أخذ الكلام"))
            voice?.cancel()
            turnAllowed = false
            duckUntil = 0
            updateDuck(now)
        }
    }

    override fun onInputText(t: String) {
        inputSinceDecision += t.length
        maybeYield(t)
        synchronized(textLock) {
            inBuf.append(t)
            val tail = inBuf.takeLast(80).toString()
            if (Ar.mentions(tail, names)) {
                val w = (cfg?.addressWindowS ?: 25) * 1000L
                StateBus.addressedUntil = System.currentTimeMillis() + w
                if (cfg?.screenMode == "on_demand" && !isFresh(System.currentTimeMillis())) {
                    exec.execute { verifyNow("نداء", false) }
                }
            }
            if (inBuf.length > 800) flushLocked()
        }
    }

    override fun onOutputText(t: String) {
        synchronized(textLock) { outBuf.append(t) }
        val v = voice
        if (v != null && v.provider == "elevenlabs") {
            decideTurn(System.currentTimeMillis())
            if (turnAllowed) {
                spokenThisTurn = true
                v.text(t)
            }
        }
    }

    override fun onTurnComplete() {
        if (turnAllowed) voice?.endTurn()
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
        if (cfg?.interruptPolicy != "always") return
        voice?.cancel()
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
            val res = if (name == "look_at_screen") {
                lookAtScreen(args.optString("reason"))
            } else Tools.exec(supa, cfg, host, name, args)
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
        if (!setupDone && extrasOn && code != -1) {
            val dropped = if (affectiveOn) "الحوار العاطفي" else "الصمت الذكي"
            if (affectiveOn) affectiveFailed = true else proactiveFailed = true
            resumeHandle = null
            AppLog.add("$dropped غير مدعوم في هذا النموذج ($code): ${reason.take(140)} — إعادة الاتصال بدونه")
            main.postDelayed({ connectLive() }, 300)
            return
        }
        lastNote = "انقطع Gemini ($code) ${reason.take(80)}"
        AppLog.add("انقطع Gemini Live ($code): ${reason.take(200)}")
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
        AppLog.add("الحالة: ${StateBus.guestEff} / مايك ${StateBus.micEff}")
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
                if (cfg?.greetOnJoin == true) {
                    StateBus.forceUntil = System.currentTimeMillis() + 25000
                    "[حالة النظام] قُبلت ضيفاً في البث والمايك مفتوح. حيِّ الحاضرين بجملة قصيرة طبيعية جداً ثم اصمت."
                } else "[حالة النظام] قُبلت ضيفاً في البث والمايك مفتوح."
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
            StateBus.speaking = now - maxOf(lastAudioAt, player?.lastEnqueueAt ?: 0L) < 700
            updateDuck(now)
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
            !connected -> Ui.GRAY
            StateBus.speaking -> Ui.BLUE
            hard == null -> Ui.GREEN
            else -> Ui.RED
        }
        val gate = when {
            diag -> "وضع اختبار الالتقاط (بلا Gemini)"
            !connected -> "جارٍ الاتصال بـ Gemini…"
            hard != null -> "ممنوع من الكلام: $hard"
            StateBus.mode == SpeakMode.ON_CALL && now > StateBus.addressedUntil && now > StateBus.forceUntil -> "جاهز، ينتظر أن يُنادى باسمه"
            else -> "مسموح له بالكلام"
        }
        val modeIdx = when (StateBus.mode) {
            SpeakMode.SILENT -> 0
            SpeakMode.ON_CALL -> 1
            SpeakMode.AUTO -> 2
        }
        val footer = "Gemini: ${if (connected) "متصل" else "غير متصل"} • الإخراج: $route" +
            (if (lastNote.isNotEmpty()) "\n$lastNote" else "")
        o.update(
            OverlayState(
                color,
                "${StateBus.guestEff.ar} • مايك ${StateBus.micEff.ar}",
                gate,
                modeIdx,
                StateBus.captureLevel * 3,
                footer,
                "ضيف: " + (StateBus.manualGuest?.ar ?: "تلقائي"),
                "مايك: " + (StateBus.manualMic?.ar ?: "تلقائي")
            )
        )
    }

    // ---------------------------------------------------------------- vision

    private val periodicTick = object : Runnable {
        override fun run() {
            if (stopping) return
            if (StateBus.shouldListen()) grabber?.grab()?.let { live?.sendVideo(it) }
            main.postDelayed(this, (cfg?.frameIntervalMs ?: 3000).toLong())
        }
    }

    private fun isFresh(now: Long): Boolean {
        if (StateBus.manualGuest != null) return true
        if (StateBus.a11yGuest != GuestState.UNKNOWN) return true
        val ttl = (cfg?.verifyTtlS ?: 60) * 1000L
        return now - lastVerifyAt < ttl
    }

    /** One screenshot -> one classification. Called only when the state matters. Blocking. */
    private fun verifyNow(reason: String, force: Boolean): Boolean {
        synchronized(verifyLock) {
            val c = cfg ?: return false
            if (!force && isFresh(System.currentTimeMillis())) return true
            val jpg = grabber?.grab()
            if (jpg == null) {
                AppLog.add("تحقق ($reason): لا توجد لقطة بعد")
                return false
            }
            val me = Prefs.get(this, "myName").ifBlank { "the user" }
            val prompt = c.visionPrompt.ifBlank { Vision.DEFAULT_PROMPT }.replace("{me}", me)
            if (visionModel.isBlank()) visionModel = LiveClient.pickTextModel(gemKey) ?: ""
            var j = Vision.classify(gemKey, visionModel, prompt, jpg)
            if (j == null && !visionFallbackTried && Vision.lastError.contains("404")) {
                visionFallbackTried = true
                val m = LiveClient.pickTextModel(gemKey)
                if (m != null && m != visionModel) {
                    AppLog.add("نموذج الرؤية $visionModel غير متاح، التحويل تلقائياً إلى $m")
                    visionModel = m
                    j = Vision.classify(gemKey, visionModel, prompt, jpg)
                }
            }
            if (j == null) {
                AppLog.add("فشل تحليل الشاشة: ${Vision.lastError}")
                return false
            }
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
            AppLog.add("تحقق ($reason): ${g.ar} / مايك ${m.ar} — ${j.optString("evidence")}")
            StateBus.visGuest = g
            StateBus.visMic = m
            if (g != GuestState.UNKNOWN) lastVerifyAt = System.currentTimeMillis()
            StateBus.recompute()
            return g != GuestState.UNKNOWN
        }
    }

    private fun lookAtScreen(reason: String): JSONObject {
        val r = JSONObject()
        if (cfg?.screenMode == "manual") {
            return r.put("shown", false).put("note", "عرض الشاشة تلقائياً متوقف. اطلب من صاحب الحساب أن يضغط «أرِه الشاشة».")
        }
        val jpg = grabber?.grab()
        if (jpg == null) return r.put("shown", false).put("note", "لا توجد لقطة متاحة الآن")
        live?.sendVideo(jpg)
        AppLog.add("لقطة بطلب الوكيل" + (if (reason.isNotBlank()) ": $reason" else ""))
        return r.put("shown", true).put("note", "أُرسلت لقطة الشاشة الحالية الآن، انظر إليها ثم أجب.")
    }

    // ---------------------------------------------------------------- panel actions

    override fun onStopSpeaking() {
        voice?.cancel()
        StateBus.silenceUntil = System.currentTimeMillis() + 30000
        turnAllowed = false
    }

    override fun onMode(m: SpeakMode) {
        StateBus.mode = m
        if (m == SpeakMode.SILENT) voice?.cancel()
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

    override fun onVerify() {
        lastNote = "جارٍ التحقق من حالتك…"
        exec.execute { verifyNow("يدوي", true) }
    }

    override fun onShowScreen() {
        exec.execute {
            val jpg = grabber?.grab()
            if (jpg != null) {
                live?.sendVideo(jpg)
                live?.sendText("[حالة النظام] أُرسلت إليك لقطة للشاشة بطلب من صاحب الحساب. انظر إليها عند الحاجة، ولا تتكلم إلا إن طُلب منك.")
                lastNote = "أُرسلت لقطة للوكيل"
                AppLog.add("لقطة يدوية أُرسلت للوكيل")
            } else lastNote = "لا توجد لقطة متاحة"
        }
    }

    override fun onCopyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text(this)))
        Toast.makeText(this, "نُسخ السجل. الصقه في المحادثة.", Toast.LENGTH_LONG).show()
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
        try { voice?.stop() } catch (e: Exception) { }
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
