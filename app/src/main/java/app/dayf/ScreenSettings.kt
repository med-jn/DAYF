package app.dayf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import app.dayf.Ui.put

class SettingsScreen(act: MainActivity) : Screen(act) {
    private val col = page()
    override val root: View = scroll(col)

    private val fields = LinkedHashMap<String, EditText>()
    private val permC = Collapsible(act, "الصلاحيات", "", false)
    private val testResult = Ui.text(act, "", 13f, Ui.MUTED)
    private val logView = Ui.text(act, "", 11f, Ui.MUTED)
    private val logC = Collapsible(act, "التشخيص والسجل", "للاختبار وتتبع الأخطاء", false)

    private lateinit var modeSeg: Segmented
    private lateinit var addr: StepperRow
    private lateinit var follow: StepperRow
    private lateinit var silence: StepperRow
    private lateinit var allowUnknown: Toggle
    private lateinit var intSeg: Segmented
    private lateinit var yieldS: StepperRow
    private lateinit var proactive: Toggle
    private lateinit var affective: Toggle
    private lateinit var screenSeg: Segmented
    private lateinit var ttl: StepperRow
    private lateinit var frame: StepperRow
    private lateinit var temp: StepperRow
    private var filled = false

    init {
        build()
    }

    private fun field(box: LinearLayout, key: String, label: String, secret: Boolean, ltr: Boolean = true, first: Boolean = false) {
        box.put(Ui.label(act, label), top = if (first) 0 else 12)
        val e = Ui.field(act, "", Prefs.get(act, key), false, ltr, secret)
        box.put(e, top = 4)
        fields[key] = e
    }

    private fun toggleRow(title: String, sub: String, t: Toggle): LinearLayout {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val tc = LinearLayout(act)
        tc.orientation = LinearLayout.VERTICAL
        tc.addView(Ui.text(act, title, 14f, Ui.TEXT))
        tc.addView(Ui.text(act, sub, 11f, Ui.MUTED))
        r.addView(tc, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        r.addView(t)
        return r
    }

    private fun build() {
        col.put(Ui.title(act, "الإعدادات", "الربط والسلوك والتشخيص"))

        // 1. keys
        val k = Collapsible(act, "الربط والمفاتيح", "تُحفظ على هذا الهاتف فقط", true)
        field(k.body, "geminiKey", "مفتاح Gemini API", true, first = true)
        field(k.body, "supaUrl", "رابط Supabase", false)
        field(k.body, "supaKey", "مفتاح Supabase العام (anon / publishable)", true)
        field(k.body, "host", "اسم المضيف الحالي (لفصل ذاكرة كل بث)", false, false)
        field(k.body, "myName", "اسم حسابك الظاهر في تيك توك", false, false)
        field(k.body, "adminKey", "رمز الإدارة (اختياري، اتركه فارغاً عادةً)", true)
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(Ui.ghostBtn(act, "حفظ") { saveFields(); act.toast("تم الحفظ على الهاتف") }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 6))
        row.addView(Ui.primaryBtn(act, "اختبار الاتصال", 14f, 11) { testConnections() }, Ui.lp(act, 0, Ui.WRAP, 1.6f, start = 6))
        k.body.put(row, top = 16)
        k.body.put(testResult, top = 12)
        col.put(k, top = 16)

        // 2. permissions
        col.put(permC, top = 12)

        // 3. when to speak
        val a = Collapsible(act, "متى يتكلم", "الوضع ونوافذ الانتباه", false)
        a.body.put(Ui.label(act, "الوضع الافتراضي عند التشغيل"))
        modeSeg = Segmented(act, listOf("صامت", "عند النداء", "ذكي"), 2) {}
        a.body.put(modeSeg.view, top = 4)
        addr = StepperRow(act, "نافذة النداء", 25.0, 5.0, 90.0, 5.0, 0, " ث")
        follow = StepperRow(act, "نافذة المتابعة بعد كلامه", 20.0, 5.0, 90.0, 5.0, 0, " ث")
        silence = StepperRow(act, "صمت يعني نهاية الجملة", 700.0, 300.0, 2000.0, 100.0, 0, " م.ث")
        a.body.put(addr.view, top = 16)
        a.body.put(follow.view, top = 10)
        a.body.put(silence.view, top = 10)
        allowUnknown = Toggle(act, false) {}
        a.body.put(toggleRow("اسمح بالكلام والحالة مجهولة", "غير مستحسن: قد يتكلم وأنت لست ضيفاً", allowUnknown), top = 14)
        col.put(a, top = 12)

        // 4. interruptions
        val m = Collapsible(act, "المقاطعة وإدارة الحوار", "كيف يتصرف حين يتكلم الناس فوقه", false)
        intSeg = Segmented(act, listOf("لا يتوقف", "ذكي", "يتوقف فوراً"), 1) {}
        m.body.put(intSeg.view)
        yieldS = StepperRow(act, "حساسية التنازل (عدد الحروف)", 30.0, 10.0, 80.0, 5.0, 0)
        m.body.put(yieldS.view, top = 14)
        proactive = Toggle(act, true) {}
        m.body.put(toggleRow("الصمت الذكي", "النموذج يقرر متى يسكت ولا يرد على ما لا يخصه", proactive), top = 14)
        affective = Toggle(act, true) {}
        m.body.put(toggleRow("الحوار العاطفي", "يتفاعل مع نبرة المتكلمين ومزاجهم", affective), top = 12)
        m.body.put(
            Ui.text(act, "«ذكي»: لا تقطعه ضجة ولا كلمة عابرة، ويتنازل حين يأخذ أحدهم الكلام فعلاً أو يقال له اسكت. «يتوقف فوراً»: للتجربة بسماعة فقط.", 11f, Ui.MUTED),
            top = 10
        )
        col.put(m, top = 12)

        // 5. screen
        val s = Collapsible(act, "لقطات الشاشة", "عند الحاجة فقط حفاظاً على الحصة", false)
        screenSeg = Segmented(act, listOf("عند الحاجة", "دوري", "يدوي فقط"), 0) {}
        s.body.put(screenSeg.view)
        ttl = StepperRow(act, "صلاحية التحقق من حالتك", 60.0, 15.0, 300.0, 15.0, 0, " ث")
        frame = StepperRow(act, "الفاصل في الوضع الدوري", 3000.0, 1000.0, 8000.0, 500.0, 0, " م.ث")
        s.body.put(ttl.view, top = 14)
        s.body.put(frame.view, top = 10)
        s.body.put(
            Ui.text(act, "عند الحاجة: يتحقق من أنك ضيف عند البدء وقبل أن يتكلم إن مضى وقت على آخر تحقق، ويطلب لقطة بنفسه إن احتاج أن يرى.", 11f, Ui.MUTED),
            top = 10
        )
        col.put(s, top = 12)

        // 6. style
        val t = Collapsible(act, "الأسلوب", "درجة الالتزام والإبداع", false)
        temp = StepperRow(act, "الحرارة (أقل = أكثر التزاماً)", 0.4, 0.1, 1.0, 0.1, 1)
        t.body.addView(temp.view)
        col.put(t, top = 12)

        col.put(Ui.primaryBtn(act, "حفظ السلوك") { saveBehavior() }, top = 20)

        // 7. diagnostics
        logC.body.put(Ui.ghostBtn(act, "🎧  اختبار التقاط الصوت (بلا Gemini)", Ui.TEXT, 13f, 11) { act.begin(true) })
        logC.body.put(Ui.ghostBtn(act, "↻  إعادة تحميل الإعدادات من Supabase", Ui.TEXT, 13f, 11) {
            Repo.load(act) { ok -> act.toast(if (ok) "تم" else Repo.error) }
        }, top = 8)
        val lh = LinearLayout(act)
        lh.orientation = LinearLayout.HORIZONTAL
        lh.addView(Ui.ghostBtn(act, "نسخ السجل", Ui.CYAN, 12f, 8) {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text(act)))
            act.toast("نُسخ السجل")
        }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 6))
        lh.addView(Ui.ghostBtn(act, "مسح السجل", Ui.MUTED, 12f, 8) { AppLog.clear(act); tick() }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 6))
        logC.body.put(lh, top = 8)
        logView.typeface = Typeface.MONOSPACE
        logView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        logView.textDirection = View.TEXT_DIRECTION_LTR
        logView.setTextIsSelectable(true)
        logC.body.put(logView, top = 12)
        col.put(logC, top = 12)
    }

    // ------------------------------------------------------------ permissions
    private fun renderPerms() {
        val items = Readiness.items(act)
        permC.setSubtitle("${items.count { it.ok }} من ${items.size} جاهز")
        permC.body.removeAllViews()
        val actions = listOf<(() -> Unit)?>(
            { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}"))) },
            { act.requestMic() },
            null,
            { Repo.load(act) { ok -> act.toast(if (ok) "تم تحميل الإعدادات" else Repo.error) } },
            { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            { act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))) }
        )
        for (i in items.indices) {
            val it = items[i]
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(statusDot(act, it.ok, it.optional), LinearLayout.LayoutParams(dp(26), Ui.WRAP))
            r.addView(Ui.text(act, it.label, 13f, if (it.ok) Ui.TEXT else Ui.MUTED), Ui.lp(act, 0, Ui.WRAP, 1f, start = 6, end = 6))
            val a = actions[i]
            if (a != null && !it.ok) {
                r.addView(Ui.ghostBtn(act, if (i == 3) "تحميل" else "تفعيل", Ui.CYAN, 12f, 6) { a() })
            }
            permC.body.put(r, top = if (i == 0) 0 else 10)
        }
    }

    // ------------------------------------------------------------ save
    private fun saveBehavior() {
        val modes = listOf("SILENT", "ON_CALL", "AUTO")
        val screens = listOf("on_demand", "periodic", "manual")
        val patch = JSONObject()
            .put("default_mode", modes[modeSeg.index])
            .put("address_window_s", addr.value.toInt())
            .put("followup_window_s", follow.value.toInt())
            .put("silence_ms", silence.value.toInt())
            .put("allow_unknown_state", allowUnknown.on)
            .put("screen_mode", screens[screenSeg.index])
            .put("verify_ttl_s", ttl.value.toInt())
            .put("frame_interval_ms", frame.value.toInt())
            .put("temperature", temp.value)
            .put("interrupt_policy", listOf("never", "smart", "always")[intSeg.index])
            .put("yield_chars", yieldS.value.toInt())
            .put("proactive_audio", proactive.on)
            .put("affective_dialog", affective.on)
        Repo.save(act, patch) { ok, msg -> act.toast(if (ok) "تم حفظ السلوك" else msg) }
    }

    private fun saveFields() {
        for ((k, e) in fields) Prefs.put(act, k, e.text.toString().trim())
    }

    // ------------------------------------------------------------ lifecycle
    override fun onShow() {
        if (!filled && (Repo.loaded || Repo.raw.length() > 0)) {
            val c = Repo.cfg()
            modeSeg.select(when (c.defaultMode) { "SILENT" -> 0; "ON_CALL" -> 1; else -> 2 }, false)
            addr.set(c.addressWindowS.toDouble())
            follow.set(c.followupWindowS.toDouble())
            silence.set(c.silenceMs.toDouble())
            allowUnknown.on = c.allowUnknown
            allowUnknown.invalidate()
            intSeg.select(when (c.interruptPolicy) { "never" -> 0; "always" -> 2; else -> 1 }, false)
            yieldS.set(c.yieldChars.toDouble())
            proactive.on = c.proactiveAudio
            proactive.invalidate()
            affective.on = c.affectiveDialog
            affective.invalidate()
            screenSeg.select(when (c.screenMode) { "periodic" -> 1; "manual" -> 2; else -> 0 }, false)
            ttl.set(c.verifyTtlS.toDouble())
            frame.set(c.frameIntervalMs.toDouble())
            temp.set(c.temperature)
            filled = true
        }
        tick()
    }

    override fun tick() {
        renderPerms()
        val t = AppLog.text(act).ifBlank { "لا يوجد شيء بعد." }
        if (logView.text.toString() != t) logView.text = t
    }

    // ------------------------------------------------------------ test
    private fun testConnections() {
        saveFields()
        testResult.text = "جارٍ الاختبار…"
        Thread {
            val sb = StringBuilder()
            val gk = Prefs.get(act, "geminiKey")
            val su = Prefs.get(act, "supaUrl")
            val sk = Prefs.get(act, "supaKey")
            var cfg = Cfg(JSONObject())
            if (su.isNotBlank() && sk.isNotBlank()) {
                val s = Supa(su, sk, Prefs.admin(act))
                val c = s.config()
                if (c != null) {
                    cfg = Cfg(c)
                    sb.append("✔ Supabase: «${cfg.name}» • قواعد ${s.rules().size} • معرفة ${s.knowledge().size}\n")
                } else sb.append("✘ Supabase: ${s.lastError}\n")
            } else sb.append("… Supabase: أدخل الرابط والمفتاح\n")
            if (gk.isNotBlank()) {
                val m = cfg.model.ifBlank { LiveClient.pickModel(gk) ?: "" }
                if (m.isBlank()) sb.append("✘ Gemini: المفتاح غير صالح أو لا يوجد نموذج Live\n")
                else {
                    Repo.liveModel = m
                    sb.append("✔ نموذج Live: $m\n")
                    val r1 = probe(gk, m, cfg, true, true)
                    if (r1.first) sb.append("✔ جلسة تعمل بالصمت الذكي والحوار العاطفي\n")
                    else {
                        val r2 = probe(gk, m, cfg, true, false)
                        if (r2.first) sb.append("✔ جلسة تعمل بالصمت الذكي، والحوار العاطفي غير مدعوم في هذا النموذج\n")
                        else {
                            val r3 = probe(gk, m, cfg, false, false)
                            if (r3.first) sb.append("✔ جلسة تعمل، لكن الصمت الذكي والحوار العاطفي غير مدعومين (سيعمل بدونهما)\n")
                            else sb.append(r3.second)
                        }
                    }
                    val vm = cfg.visionModel.ifBlank { LiveClient.pickTextModel(gk) ?: "" }
                    sb.append("• نموذج الرؤية: ${if (vm.isBlank()) "غير متاح" else vm}\n")
                }
            } else sb.append("… Gemini: أدخل المفتاح\n")
            Repo.load(act) {}
            val out = sb.toString().trim()
            AppLog.add("اختبار الاتصال:\n$out")
            act.runOnUiThread { testResult.text = out }
        }.start()
    }

    private fun probe(key: String, model: String, cfg: Cfg, extras: Boolean, affective: Boolean): Pair<Boolean, String> {
        val latch = CountDownLatch(1)
        var ok = false
        var msg = "✘ Gemini Live: انتهت المهلة بلا رد\n"
        val client = LiveClient(key, object : LiveClient.Listener {
            override fun onSetupComplete() {
                ok = true
                latch.countDown()
            }
            override fun onAudio(pcm24k: ByteArray) {}
            override fun onInputText(t: String) {}
            override fun onOutputText(t: String) {}
            override fun onTurnComplete() {}
            override fun onInterrupted() {}
            override fun onToolCall(id: String, name: String, args: JSONObject) {}
            override fun onResumeHandle(h: String) {}
            override fun onGoAway() {}
            override fun onClosed(code: Int, reason: String) {
                if (latch.count > 0) {
                    msg = "✘ Gemini Live أُغلقت ($code): ${reason.take(180)}\n"
                    latch.countDown()
                }
            }
        })
        client.connect(Prompt.setup(model, cfg, "اختبار", null, extras, false, affective), extras || affective)
        latch.await(12, TimeUnit.SECONDS)
        client.close()
        return ok to msg
    }
}
