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
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import app.dayf.Ui.put

class SettingsScreen(act: MainActivity) : Screen(act) {
    private val col = page()
    override val root: View = scroll(col)

    private lateinit var seg: Segmented
    private val boxLink = LinearLayout(act)
    private val boxBehave = LinearLayout(act)
    private val boxDiag = LinearLayout(act)
    private val fields = LinkedHashMap<String, EditText>()
    private val permBox = LinearLayout(act)
    private val testResult = Ui.text(act, "", 13f, Ui.MUTED)
    private val logView = Ui.text(act, "", 11f, Ui.MUTED)

    private lateinit var modeSeg: Segmented
    private lateinit var addr: StepperRow
    private lateinit var follow: StepperRow
    private lateinit var silence: StepperRow
    private lateinit var allowUnknown: Toggle
    private lateinit var screenSeg: Segmented
    private lateinit var ttl: StepperRow
    private lateinit var frame: StepperRow
    private lateinit var temp: StepperRow
    private lateinit var intSeg: Segmented
    private lateinit var yieldS: StepperRow
    private lateinit var proactive: Toggle
    private var filled = false

    init {
        build()
    }

    private fun build() {
        col.put(Ui.title(act, "الإعدادات", "الربط والسلوك والتشخيص"))
        col.put(Ornament(act), top = 10, h = dp(16))
        seg = Segmented(act, listOf("الربط", "السلوك", "التشخيص"), 0) { show(it) }
        col.put(seg.view, top = 2)

        buildLink()
        buildBehave()
        buildDiag()
        col.put(boxLink, top = 12)
        col.put(boxBehave, top = 12)
        col.put(boxDiag, top = 12)
        show(0)
    }

    private fun show(i: Int) {
        boxLink.visibility = if (i == 0) View.VISIBLE else View.GONE
        boxBehave.visibility = if (i == 1) View.VISIBLE else View.GONE
        boxDiag.visibility = if (i == 2) View.VISIBLE else View.GONE
    }

    private fun field(box: LinearLayout, key: String, label: String, secret: Boolean, ltr: Boolean = true) {
        box.put(Ui.label(act, label), top = 12)
        val e = Ui.field(act, "", Prefs.get(act, key), false, ltr, secret)
        box.put(e, top = 4)
        fields[key] = e
    }

    // ------------------------------------------------------------ link
    private fun buildLink() {
        boxLink.orientation = LinearLayout.VERTICAL
        val k = Ui.panel(act)
        k.addView(Ui.sectionHead(act, "المفاتيح"))
        field(k, "geminiKey", "مفتاح Gemini API", true)
        field(k, "supaUrl", "رابط Supabase", false)
        field(k, "supaKey", "مفتاح Supabase العام (anon / publishable)", true)
        field(k, "adminKey", "رمز الإدارة (اختياري: اتركه فارغاً إن لم تغيّر الكلمة في ملف SQL)", true)
        field(k, "host", "اسم المضيف الحالي (لفصل ذاكرة كل بث)", false, false)
        field(k, "myName", "اسم حسابك الظاهر في تيك توك (ليعرفك في الشاشة)", false, false)
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(Ui.ghostBtn(act, "حفظ") { saveFields(); act.toast("تم الحفظ على الهاتف") }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 6))
        row.addView(Ui.goldBtn(act, "اختبار الاتصال", 14f, 11) { testConnections() }, Ui.lp(act, 0, Ui.WRAP, 1.6f, start = 6))
        k.put(row, top = 16)
        k.put(testResult, top = 12)
        boxLink.put(k)

        val p = Ui.panel(act)
        p.addView(Ui.sectionHead(act, "الصلاحيات"))
        permBox.orientation = LinearLayout.VERTICAL
        p.put(permBox, top = 4)
        boxLink.put(p, top = 14)
    }

    private fun renderPerms() {
        permBox.removeAllViews()
        val actions = listOf<(() -> Unit)?>(
            { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}"))) },
            { act.requestMic() },
            null,
            { Repo.load(act) { ok -> act.toast(if (ok) "تم تحميل الإعدادات" else Repo.error) } },
            { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            { act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))) }
        )
        val items = Readiness.items(act)
        for (i in items.indices) {
            val it = items[i]
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(statusDot(act, it.ok, it.optional), LinearLayout.LayoutParams(dp(26), Ui.WRAP))
            r.addView(Ui.text(act, it.label, 13f, if (it.ok) Ui.TEXT else Ui.MUTED), Ui.lp(act, 0, Ui.WRAP, 1f, start = 6, end = 6))
            val a = actions[i]
            if (a != null && !it.ok) {
                r.addView(Ui.ghostBtn(act, if (i == 3) "تحميل" else "تفعيل", Ui.GOLD_L, 12f, 6) { a() })
            }
            permBox.put(r, top = 10)
        }
    }

    // ------------------------------------------------------------ behavior
    private fun buildBehave() {
        boxBehave.orientation = LinearLayout.VERTICAL

        val a = Ui.panel(act)
        a.addView(Ui.sectionHead(act, "متى يتكلم"))
        a.put(Ui.label(act, "الوضع الافتراضي عند التشغيل"), top = 10)
        modeSeg = Segmented(act, listOf("صامت", "عند النداء", "ذكي"), 1) {}
        a.put(modeSeg.view, top = 4)
        addr = StepperRow(act, "نافذة النداء", 25.0, 5.0, 90.0, 5.0, 0, " ث")
        follow = StepperRow(act, "نافذة المتابعة بعد كلامه", 20.0, 5.0, 90.0, 5.0, 0, " ث")
        silence = StepperRow(act, "صمت يعني نهاية الجملة", 800.0, 300.0, 2000.0, 100.0, 0, " م.ث")
        a.put(addr.view, top = 16)
        a.put(follow.view, top = 10)
        a.put(silence.view, top = 10)
        val ur = LinearLayout(act)
        ur.orientation = LinearLayout.HORIZONTAL
        ur.gravity = Gravity.CENTER_VERTICAL
        val ut = LinearLayout(act)
        ut.orientation = LinearLayout.VERTICAL
        ut.addView(Ui.text(act, "اسمح بالكلام والحالة مجهولة", 14f, Ui.TEXT))
        ut.addView(Ui.text(act, "غير مستحسن: قد يتكلم وأنت لست ضيفاً", 11f, Ui.MUTED))
        ur.addView(ut, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        allowUnknown = Toggle(act, false) {}
        ur.addView(allowUnknown)
        a.put(ur, top = 14)
        boxBehave.put(a)

        val s = Ui.panel(act)
        s.addView(Ui.sectionHead(act, "لقطات الشاشة"))
        s.put(Ui.text(act, "اللقطة تُلتقط وتُرسل عند الحاجة فقط حفاظاً على الحصة.", 12f, Ui.MUTED), top = 6)
        screenSeg = Segmented(act, listOf("عند الحاجة", "دوري", "يدوي فقط"), 0) {}
        s.put(screenSeg.view, top = 10)
        ttl = StepperRow(act, "صلاحية التحقق من حالتك", 60.0, 15.0, 300.0, 15.0, 0, " ث")
        frame = StepperRow(act, "الفاصل في الوضع الدوري", 3000.0, 1000.0, 8000.0, 500.0, 0, " م.ث")
        s.put(ttl.view, top = 14)
        s.put(frame.view, top = 10)
        s.put(
            Ui.text(act, "عند الحاجة: يتحقق من أنك ضيف عند البدء وقبل أن يتكلم إن مضى وقت على آخر تحقق، ويطلب الوكيل لقطة بنفسه إن احتاج أن يرى.", 11f, Ui.MUTED),
            top = 10
        )
        boxBehave.put(s, top = 14)

        val m = Ui.panel(act)
        m.addView(Ui.sectionHead(act, "المقاطعة وإدارة الحوار"))
        m.put(Ui.text(act, "كيف يتصرف حين يتكلم الناس فوقه", 12f, Ui.MUTED), top = 6)
        intSeg = Segmented(act, listOf("لا يتوقف", "ذكي", "يتوقف فوراً"), 1) {}
        m.put(intSeg.view, top = 10)
        yieldS = StepperRow(act, "حساسية التنازل (عدد الحروف)", 30.0, 10.0, 80.0, 5.0, 0)
        m.put(yieldS.view, top = 14)
        val pr = LinearLayout(act)
        pr.orientation = LinearLayout.HORIZONTAL
        pr.gravity = Gravity.CENTER_VERTICAL
        val pt = LinearLayout(act)
        pt.orientation = LinearLayout.VERTICAL
        pt.addView(Ui.text(act, "الصمت الذكي", 14f, Ui.TEXT))
        pt.addView(Ui.text(act, "النموذج نفسه يقرر متى يسكت ولا يرد على ما لا يخصه", 11f, Ui.MUTED))
        pr.addView(pt, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        proactive = Toggle(act, true) {}
        pr.addView(proactive)
        m.put(pr, top = 14)
        m.put(
            Ui.text(act, "«ذكي»: لا تقطعه ضجة ولا كلمة عابرة، ويتنازل حين يأخذ أحدهم الكلام فعلاً أو يقول له اسكت. «لا يتوقف»: يكمل دائماً. «يتوقف فوراً»: أي صوت يقطعه (للتجربة بسماعة فقط).", 11f, Ui.MUTED),
            top = 10
        )
        boxBehave.put(m, top = 14)

        val t = Ui.panel(act)
        t.addView(Ui.sectionHead(act, "الأسلوب"))
        temp = StepperRow(act, "الحرارة (أقل = أكثر التزاماً)", 0.4, 0.1, 1.0, 0.1, 1)
        t.put(temp.view, top = 12)
        boxBehave.put(t, top = 14)

        boxBehave.put(Ui.goldBtn(act, "حفظ السلوك") { saveBehavior() }, top = 18)
    }

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
        Repo.save(act, patch) { ok, msg -> act.toast(if (ok) "تم حفظ السلوك" else msg) }
    }

    // ------------------------------------------------------------ diagnostics
    private fun buildDiag() {
        boxDiag.orientation = LinearLayout.VERTICAL
        val a = Ui.panel(act)
        a.addView(Ui.sectionHead(act, "أدوات"))
        a.put(Ui.ghostBtn(act, "🎧  اختبار التقاط الصوت (بلا Gemini)") { act.begin(true) }, top = 10)
        a.put(Ui.ghostBtn(act, "🎙  استوديو المحادثة") { act.openStudio() }, top = 8)
        a.put(Ui.ghostBtn(act, "↻  إعادة تحميل الإعدادات من Supabase") {
            Repo.load(act) { ok -> act.toast(if (ok) "تم" else Repo.error) }
        }, top = 8)
        boxDiag.put(a)

        val l = Ui.panel(act)
        val lh = LinearLayout(act)
        lh.orientation = LinearLayout.HORIZONTAL
        lh.gravity = Gravity.CENTER_VERTICAL
        lh.addView(Ui.sectionHead(act, "السجل"), Ui.lp(act, 0, Ui.WRAP, 1f))
        lh.addView(Ui.ghostBtn(act, "نسخ", Ui.GOLD_L, 12f, 6) {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text(act)))
            act.toast("نُسخ السجل")
        }, Ui.lp(act, Ui.WRAP, Ui.WRAP, end = 6))
        lh.addView(Ui.ghostBtn(act, "مسح", Ui.MUTED, 12f, 6) { AppLog.clear(act); tick() })
        l.addView(lh)
        logView.typeface = Typeface.MONOSPACE
        logView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        logView.textDirection = View.TEXT_DIRECTION_LTR
        logView.setTextIsSelectable(true)
        l.put(logView, top = 10)
        boxDiag.put(l, top = 14)
    }

    // ------------------------------------------------------------ lifecycle
    override fun onShow() {
        if (!filled && (Repo.loaded || Repo.raw.length() > 0)) {
            val c = Repo.cfg()
            filled = true
            boxBehave.removeAllViews()
            buildBehaveWith(c)
        }
        tick()
    }

    private fun buildBehaveWith(c: Cfg) {
        buildBehave()
        modeSeg.select(when (c.defaultMode) { "SILENT" -> 0; "AUTO" -> 2; else -> 1 }, false)
        addr = replaceStepper(addr, StepperRow(act, "نافذة النداء", c.addressWindowS.toDouble(), 5.0, 90.0, 5.0, 0, " ث"))
        follow = replaceStepper(follow, StepperRow(act, "نافذة المتابعة بعد كلامه", c.followupWindowS.toDouble(), 5.0, 90.0, 5.0, 0, " ث"))
        silence = replaceStepper(silence, StepperRow(act, "صمت يعني نهاية الجملة", c.silenceMs.toDouble(), 300.0, 2000.0, 100.0, 0, " م.ث"))
        ttl = replaceStepper(ttl, StepperRow(act, "صلاحية التحقق من حالتك", c.verifyTtlS.toDouble(), 15.0, 300.0, 15.0, 0, " ث"))
        frame = replaceStepper(frame, StepperRow(act, "الفاصل في الوضع الدوري", c.frameIntervalMs.toDouble(), 1000.0, 8000.0, 500.0, 0, " م.ث"))
        temp = replaceStepper(temp, StepperRow(act, "الحرارة (أقل = أكثر التزاماً)", c.temperature, 0.1, 1.0, 0.1, 1))
        allowUnknown.on = c.allowUnknown
        allowUnknown.invalidate()
        intSeg.select(when (c.interruptPolicy) { "never" -> 0; "always" -> 2; else -> 1 }, false)
        yieldS = replaceStepper(yieldS, StepperRow(act, "حساسية التنازل (عدد الحروف)", c.yieldChars.toDouble(), 10.0, 80.0, 5.0, 0))
        proactive.on = c.proactiveAudio
        proactive.invalidate()
        screenSeg.select(when (c.screenMode) { "periodic" -> 1; "manual" -> 2; else -> 0 }, false)
    }

    private fun replaceStepper(old: StepperRow, new: StepperRow): StepperRow {
        val parent = old.view.parent as? LinearLayout ?: return old
        val idx = parent.indexOfChild(old.view)
        val lp = old.view.layoutParams
        parent.removeView(old.view)
        parent.addView(new.view, idx, lp)
        return new
    }

    private fun saveFields() {
        for ((k, e) in fields) Prefs.put(act, k, e.text.toString().trim())
    }

    override fun tick() {
        if (boxLink.visibility == View.VISIBLE) renderPerms()
        if (boxDiag.visibility == View.VISIBLE) {
            val t = AppLog.text(act).ifBlank { "لا يوجد شيء بعد." }
            if (logView.text.toString() != t) logView.text = t
        }
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
                    val r1 = probe(gk, m, cfg, true)
                    if (r1.first) sb.append("✔ جلسة Gemini Live تعمل مع «الصمت الذكي»\n")
                    else {
                        val r2 = probe(gk, m, cfg, false)
                        if (r2.first) sb.append("✔ جلسة تعمل، لكن «الصمت الذكي» غير مدعوم في هذا النموذج (سيعمل بدونه)\n")
                        else sb.append(r2.second)
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

    private fun probe(key: String, model: String, cfg: Cfg, extras: Boolean): Pair<Boolean, String> {
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
        client.connect(Prompt.setup(model, cfg, "اختبار", null, extras, false), extras)
        latch.await(12, TimeUnit.SECONDS)
        client.close()
        return ok to msg
    }
}
