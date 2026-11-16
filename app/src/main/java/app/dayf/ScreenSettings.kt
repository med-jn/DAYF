package app.dayf

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
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
    private val permC = Collapsible(act, "الصلاحيات", "", true)
    private val accountC = Collapsible(act, "الحساب", "", false)
    private val keyC = Collapsible(act, "مفتاح Gemini", "", false)
    private val keyField = Ui.field(act, "الصق المفتاح هنا", "", false, true, true)
    private val keyStatus = Ui.text(act, "", 13f, Ui.MUTED)
    private val clipBtn = Ui.iconSolid(act, Ic.CHECK, "", Ui.CARD2, Ui.CYAN, 13f, 11) { useClipboard() }
    private val results = LinearLayout(act)
    private val logView = Ui.text(act, "", 11f, Ui.MUTED)
    private val aboutC = Collapsible(act, "حول التطبيق", "", false)
    private val logC = Collapsible(act, "التشخيص والسجل", "للاختبار وتتبع الأخطاء", false)
    private val modelValue = Ui.text(act, "", 13f, Ui.CYAN, true)
    private var clipKey = ""
    private var keyFlow = false
    private val perms = ArrayList<Pair<Toggle, () -> Boolean>>()

    private lateinit var hostGender: Segmented
    private lateinit var modeSeg: Segmented
    private lateinit var addr: StepperRow
    private lateinit var follow: StepperRow
    private lateinit var allowUnknown: Toggle
    private lateinit var intSeg: Segmented
    private lateinit var yieldS: StepperRow
    private lateinit var proactive: Toggle
    private lateinit var affective: Toggle
    private lateinit var fast: Toggle
    private lateinit var speedSeg: Segmented
    private lateinit var memory: Toggle
    private lateinit var screenSeg: Segmented
    private lateinit var ttl: StepperRow
    private lateinit var frame: StepperRow
    private lateinit var temp: StepperRow
    private var filled = false

    init {
        build()
    }

    override fun reset() {
        filled = false
    }

    private fun row(title: String, sub: String, t: Toggle): LinearLayout {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val tc = LinearLayout(act)
        tc.orientation = LinearLayout.VERTICAL
        tc.addView(Ui.text(act, title, 14f, Ui.TEXT))
        if (sub.isNotBlank()) tc.addView(Ui.text(act, sub, 11f, Ui.MUTED))
        r.addView(tc, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        r.addView(t)
        return r
    }

    private fun permRow(title: String, sub: String, granted: () -> Boolean, request: () -> Unit) {
        val t = Toggle(act, granted()) {}
        t.onTap = request
        perms.add(t to granted)
        permC.body.put(row(title, sub, t), top = if (perms.size == 1) 0 else 14)
    }

    private fun build() {
        col.put(Ui.title(act, "الإعدادات", "الصلاحيات والحساب والسلوك"))

        // ---- 1. permissions first: nothing works without them
        permRow("الظهور فوق التطبيقات", "اللوحة العائمة فوق تيك توك", { Settings.canDrawOverlays(act) }) {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
        }
        permRow("الميكروفون", "لالتقاط صوت الجهاز الداخلي", {
            act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }) { act.requestMic() }
        if (Build.VERSION.SDK_INT >= 33) {
            permRow("الإشعارات", "إشعار التشغيل الدائم", {
                act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }) { act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43) }
        }
        permRow("استثناء البطارية", "يبقيه يعمل في الخلفية دون أن يقتله النظام", {
            (act.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(act.packageName)
        }) {
            act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}")))
        }
        col.put(permC, top = 16)

        // ---- 2. account
        accountC.body.put(Ui.text(act, "", 13f, Ui.MUTED))
        col.put(accountC, top = 12)

        // ---- 3. Gemini key (guided)
        keyC.body.put(Ui.text(act, "مفتاحك الخاص يمنحك حصتك المجانية من Google. ثلاث خطوات:", 13f, Ui.MUTED))
        keyC.body.put(Ui.text(act, "1) افتح صفحة المفاتيح وسجّل بحساب جوجل (أول مرة فقط تطلب Google موافقتها).", 12f, Ui.TEXT), top = 10)
        keyC.body.put(Ui.text(act, "2) أنشئ مفتاحاً واضغط نسخ. سيظهر فوق المتصفح زر للعودة.", 12f, Ui.TEXT), top = 4)
        keyC.body.put(Ui.text(act, "3) اضغط زر العودة: يُكتشف المفتاح ويُختبر ويُحفظ تلقائياً.", 12f, Ui.TEXT), top = 4)
        keyC.body.put(Ui.iconPrimary(act, Ic.LINK, "افتح صفحة المفاتيح", 14f, 12) { openKeyPage() }, top = 14)
        clipBtn.visibility = View.GONE
        keyC.body.put(clipBtn, top = 10)
        keyC.body.put(Ui.label(act, "أو الصق المفتاح يدوياً"), top = 14)
        keyC.body.put(keyField, top = 4)
        keyC.body.put(Ui.iconGhost(act, Ic.CHECK, "اختبار وحفظ", Ui.TEXT, 14f, 12) { saveKey(keyField.text.toString().trim()) }, top = 10)
        keyC.body.put(keyStatus, top = 10)
        col.put(keyC, top = 12)

        // ---- 4. broadcast info
        val b = Collapsible(act, "معلومات البث", "اسم المضيف وجنسه", false)
        b.body.put(Ui.label(act, "اسم المضيف (يُستعمل للمخاطبة ولفصل ذاكرة كل بث)"))
        val host = Ui.field(act, "", Prefs.get(act, "host"))
        b.body.put(host, top = 4)
        fields["host"] = host
        b.body.put(Ui.label(act, "جنس المضيف (لتصحيح صيغة المخاطبة بالعربية)"), top = 12)
        hostGender = Segmented(act, listOf("ذكر", "أنثى"), if (Prefs.get(act, "hostGender") == "female") 1 else 0) {}
        b.body.put(hostGender.view, top = 4)
        b.body.put(Ui.label(act, "اسم حسابك الظاهر في تيك توك (ليعرفك في الشاشة)"), top = 12)
        val me = Ui.field(act, "", Prefs.get(act, "myName"))
        b.body.put(me, top = 4)
        fields["myName"] = me
        b.body.put(Ui.iconGhost(act, Ic.CHECK, "حفظ", Ui.TEXT, 13f, 10) { saveFields(); act.toast("تم الحفظ") }, top = 12)
        col.put(b, top = 12)

        // ---- 5. speed and model
        val sp = Collapsible(act, "سرعة الاستجابة والنموذج", "إن كان الرد بطيئاً ابدأ من هنا", false)
        sp.body.put(Ui.label(act, "سرعة الرد بعد انتهاء المتكلم"))
        speedSeg = Segmented(act, listOf("سريع", "متوازن", "متأنٍ"), 1) {}
        sp.body.put(speedSeg.view, top = 4)
        fast = Toggle(act, true) {}
        sp.body.put(row("وضع السرعة", "يوقف التفكير الداخلي للنموذج لرد أسرع", fast), top = 14)
        sp.body.put(Ui.label(act, "النموذج الصوتي"), top = 14)
        val mr = LinearLayout(act)
        mr.orientation = LinearLayout.HORIZONTAL
        mr.gravity = Gravity.CENTER_VERTICAL
        mr.addView(modelValue, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        mr.addView(Ui.iconGhost(act, Ic.SLIDERS, "تغيير", Ui.CYAN, 12f, 8) { chooseModel() })
        sp.body.put(mr, top = 6)
        sp.body.put(Ui.text(act, "النماذج الأحدث ليست دائماً الأسرع. جرّب نموذجاً من عائلة flash إن شعرت بتأخر.", 11f, Ui.MUTED), top = 8)
        col.put(sp, top = 12)

        // ---- 6. when to speak
        val a = Collapsible(act, "متى يتكلم", "الوضع ونوافذ الانتباه", false)
        a.body.put(Ui.label(act, "الوضع الافتراضي عند التشغيل"))
        modeSeg = Segmented(act, listOf("صامت", "عند النداء", "ذكي"), 2) {}
        a.body.put(modeSeg.view, top = 4)
        addr = StepperRow(act, "نافذة النداء", 25.0, 5.0, 90.0, 5.0, 0, " ث")
        follow = StepperRow(act, "نافذة المتابعة بعد كلامه", 20.0, 5.0, 90.0, 5.0, 0, " ث")
        a.body.put(addr.view, top = 16)
        a.body.put(follow.view, top = 10)
        allowUnknown = Toggle(act, false) {}
        a.body.put(row("اسمح بالكلام والحالة مجهولة", "غير مستحسن: قد يتكلم وأنت لست ضيفاً", allowUnknown), top = 14)
        col.put(a, top = 12)

        // ---- 7. interruptions
        val m = Collapsible(act, "المقاطعة وإدارة الحوار", "كيف يتصرف حين يتكلم الناس فوقه", false)
        intSeg = Segmented(act, listOf("لا يتوقف", "ذكي", "يتوقف فوراً"), 1) {}
        m.body.put(intSeg.view)
        yieldS = StepperRow(act, "حساسية التنازل (عدد الحروف)", 30.0, 10.0, 80.0, 5.0, 0)
        m.body.put(yieldS.view, top = 14)
        proactive = Toggle(act, true) {}
        m.body.put(row("الصمت الذكي", "النموذج يقرر متى يسكت ولا يرد على ما لا يخصه", proactive), top = 14)
        affective = Toggle(act, true) {}
        m.body.put(row("الحوار العاطفي", "يتفاعل مع نبرة المتكلمين ومزاجهم", affective), top = 12)
        col.put(m, top = 12)

        // ---- 8. screen
        val s = Collapsible(act, "لقطات الشاشة", "عند الحاجة فقط حفاظاً على الحصة والخصوصية", false)
        screenSeg = Segmented(act, listOf("عند الحاجة", "دوري", "يدوي فقط"), 0) {}
        s.body.put(screenSeg.view)
        ttl = StepperRow(act, "صلاحية التحقق من حالتك", 180.0, 15.0, 600.0, 15.0, 0, " ث")
        frame = StepperRow(act, "الفاصل في الوضع الدوري", 3000.0, 1000.0, 8000.0, 500.0, 0, " م.ث")
        s.body.put(ttl.view, top = 14)
        s.body.put(frame.view, top = 10)
        s.body.put(Ui.text(act, "اللقطة تُرسل إلى Gemini للتحقق فقط ولا تُحفظ عندنا أبداً.", 11f, Ui.MUTED), top = 10)
        col.put(s, top = 12)

        // ---- 9. style and memory
        val t = Collapsible(act, "الأسلوب والذاكرة", "درجة الالتزام وحفظ الملاحظات", false)
        temp = StepperRow(act, "الحرارة (أقل = أكثر التزاماً)", 0.4, 0.1, 1.0, 0.1, 1)
        t.body.addView(temp.view)
        memory = Toggle(act, false) {}
        t.body.put(
            row("حفظ ملاحظات عن الأشخاص", "اختياري ومعطّل افتراضياً. لا نحفظ كلام الآخرين أبداً", memory),
            top = 14
        )
        col.put(t, top = 12)

        col.put(Ui.iconPrimary(act, Ic.CHECK, "حفظ الإعدادات") { saveBehavior() }, top = 20)

        // ---- 10. diagnostics
        logC.body.put(Ui.iconGhost(act, Ic.PLUG, "اختبار الاتصال بـ Gemini", Ui.TEXT, 13f, 11) { testConnections() })
        results.orientation = LinearLayout.VERTICAL
        logC.body.put(results, top = 10)
        logC.body.put(Ui.iconGhost(act, Ic.HEADPHONES, "اختبار التقاط الصوت بلا Gemini", Ui.TEXT, 13f, 11) { act.begin(true) }, top = 10)
        logC.body.put(Ui.iconGhost(act, Ic.REFRESH, "إعادة تحميل الإعدادات من حسابك", Ui.TEXT, 13f, 11) {
            Repo.load(act) { ok -> act.toast(if (ok) "تم" else Repo.error) }
        }, top = 8)
        val lh = LinearLayout(act)
        lh.orientation = LinearLayout.HORIZONTAL
        lh.addView(Ui.iconGhost(act, Ic.COPY, "نسخ السجل", Ui.CYAN, 12f, 8) {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", AppLog.text(act)))
            act.toast("نُسخ السجل")
        }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 6))
        lh.addView(Ui.iconGhost(act, Ic.TRASH, "مسح السجل", Ui.MUTED, 12f, 8) { AppLog.clear(act); tick() }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 6))
        logC.body.put(lh, top = 8)
        logView.typeface = Typeface.MONOSPACE
        logView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        logView.textDirection = View.TEXT_DIRECTION_LTR
        logView.setTextIsSelectable(true)
        logC.body.put(logView, top = 12)
        col.put(logC, top = 12)
        col.put(aboutC, top = 12)
        try {
            val pi = act.packageManager.getPackageInfo(act.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            aboutC.body.put(Ui.text(act, "Tik Guest", 15f, Ui.TEXT, true))
            aboutC.body.put(Ui.text(act, "الإصدار ${pi.versionName ?: ""}", 13f, Ui.CYAN, true), top = 6)
            aboutC.body.put(Ui.text(act, "رقم البناء $code", 12f, Ui.MUTED), top = 4)
            val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(pi.lastUpdateTime))
            aboutC.body.put(Ui.text(act, "آخر تحديث للتطبيق: $d", 12f, Ui.MUTED), top = 4)
        } catch (e: Exception) {
            aboutC.body.put(Ui.text(act, "Tik Guest", 15f, Ui.TEXT, true))
        }
    }

    // ------------------------------------------------------------ account
    private fun renderAccount() {
        accountC.body.removeAllViews()
        val email = Auth.email(act)
        accountC.setSubtitle(email)
        accountC.body.put(Ui.text(act, if (email.isBlank()) "غير مسجّل" else "مسجّل بـ $email", 13f, Ui.MUTED))
        accountC.body.put(Ui.iconGhost(act, Ic.LOGOUT, "تسجيل الخروج", Ui.TEXT, 13f, 11) {
            Dialogs.confirm(act, "تسجيل الخروج", "ستبقى بياناتك في حسابك وتعود عند الدخول.", "خروج") { act.signOut() }
        }, top = 12)
        if (Config.PRIVACY_URL.isNotBlank()) {
            accountC.body.put(Ui.iconGhost(act, Ic.LINK, "سياسة الخصوصية", Ui.CYAN, 13f, 11) {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.PRIVACY_URL)))
            }, top = 8)
        }
        accountC.body.put(Ui.iconSolid(act, Ic.TRASH, "حذف حسابي وكل بياناتي", Ui.CARD2, Ui.RED, 13f, 11) {
            Dialogs.confirm(
                act, "حذف الحساب",
                "سيُحذف حسابك وكل شخصياتك وقواعدك ومعارفك ومفاتيحك المحفوظة نهائياً، ولا يمكن التراجع.", "حذف نهائياً"
            ) { deleteAccount() }
        }, top = 8)
    }

    private fun deleteAccount() {
        Thread {
            val s = Repo.supa(act)
            val r = s?.deleteAccount()
            act.runOnUiThread {
                if (r != null && r.first) {
                    SecureStore.remove(act, "gemini")
                    SecureStore.remove(act, "eleven")
                    act.toast("تم حذف حسابك")
                    act.signOut()
                } else act.toast("تعذّر الحذف: ${s?.lastError ?: ""}")
            }
        }.start()
    }

    // ------------------------------------------------------------ Gemini key
    private fun looksLikeKey(t: String): Boolean {
        val x = t.trim()
        return x.length in 30..90 && !x.contains(' ') && !x.contains('\n') && (x.startsWith("AIza") || x.startsWith("AQ."))
    }

    private fun openKeyPage() {
        keyFlow = true
        keyStatus.setTextColor(Ui.MUTED)
        keyStatus.text = ""
        KeyReturn.show(act)
        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.KEYS_URL)))
    }

    private fun readClipboard(): String {
        return try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** Called when the app returns to the front. */
    override fun onResume() {
        KeyReturn.hide(act)
        val t = readClipboard()
        if (looksLikeKey(t) && t != clipKey) {
            if (keyFlow) {
                clipKey = t
                saveKey(t)
            } else if (Secrets.gemini(act).isBlank()) {
                clipKey = t
                clipBtn.setLabel("استعمل المفتاح المنسوخ (…" + t.takeLast(4) + ")")
                clipBtn.visibility = View.VISIBLE
            }
        }
        keyFlow = false
    }

    private fun useClipboard() {
        if (clipKey.isNotBlank()) saveKey(clipKey)
    }

    private fun saveKey(key: String) {
        if (key.isBlank()) {
            act.toast("الصق المفتاح أولاً")
            return
        }
        keyStatus.setTextColor(Ui.MUTED)
        keyStatus.text = "جارٍ اختبار المفتاح..."
        Thread {
            LiveClient.forgetModel()
            val m = LiveClient.pickModel(key)
            act.runOnUiThread {
                if (m == null) {
                    keyStatus.setTextColor(Ui.RED)
                    keyStatus.text = "المفتاح غير صالح أو لا يدعم Live. تأكد من نسخه كاملاً."
                } else {
                    Repo.liveModel = m
                    Secrets.save(act, "gemini", key)
                    keyField.setText("")
                    clipBtn.visibility = View.GONE
                    keyStatus.setTextColor(Ui.GREEN)
                    keyStatus.text = "المفتاح صالح ومحفوظ ومشفّر في حسابك."
                    keyC.setSubtitle("محفوظ ومشفّر")
                    act.toast("تم حفظ المفتاح")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ model
    private fun chooseModel() {
        val key = Secrets.gemini(act)
        if (key.isBlank()) {
            act.toast("أضف مفتاح Gemini أولاً")
            return
        }
        act.toast("جارٍ جلب النماذج...")
        Thread {
            val list = LiveClient.listLiveModels(key)
            act.runOnUiThread {
                if (list.isEmpty()) {
                    act.toast("لا توجد نماذج متاحة لمفتاحك")
                } else {
                    val cur = Repo.cfg().model
                    val items = listOf("تلقائي (الأنسب سرعةً)") + list
                    Dialogs.pick(act, "النموذج الصوتي", items, if (cur.isBlank()) 0 else items.indexOf(cur).coerceAtLeast(0)) { i ->
                        val v = if (i == 0) "" else list[i - 1]
                        Repo.save(act, JSONObject().put("model", v)) { ok, msg ->
                            act.toast(if (ok) "تم اختيار النموذج" else msg)
                            showModel()
                        }
                    }
                }
            }
        }.start()
    }

    private fun showModel() {
        val m = Repo.cfg().model
        modelValue.text = if (m.isBlank()) "تلقائي" + (if (Repo.liveModel.isNotBlank()) ": " + Repo.liveModel else "") else m
        modelValue.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    // ------------------------------------------------------------ save
    private fun saveBehavior() {
        val modes = listOf("SILENT", "ON_CALL", "AUTO")
        val screens = listOf("on_demand", "periodic", "manual")
        val speeds = listOf("fast", "balanced", "patient")
        val patch = JSONObject()
            .put("default_mode", modes[modeSeg.index])
            .put("address_window_s", addr.value.toInt())
            .put("followup_window_s", follow.value.toInt())
            .put("response_speed", speeds[speedSeg.index])
            .put("fast_mode", fast.on)
            .put("allow_unknown_state", allowUnknown.on)
            .put("screen_mode", screens[screenSeg.index])
            .put("verify_ttl_s", ttl.value.toInt())
            .put("frame_interval_ms", frame.value.toInt())
            .put("temperature", temp.value)
            .put("interrupt_policy", listOf("never", "smart", "always")[intSeg.index])
            .put("yield_chars", yieldS.value.toInt())
            .put("proactive_audio", proactive.on)
            .put("affective_dialog", affective.on)
            .put("store_memory", memory.on)
        Repo.save(act, patch) { ok, msg -> act.toast(if (ok) "تم حفظ الإعدادات" else msg) }
    }

    private fun saveFields() {
        for ((k, e) in fields) Prefs.put(act, k, e.text.toString().trim())
        Prefs.put(act, "hostGender", if (hostGender.index == 1) "female" else "male")
    }

    // ------------------------------------------------------------ lifecycle
    override fun onShow() {
        if (!filled && (Repo.loaded || Repo.raw.length() > 0)) {
            val c = Repo.cfg()
            modeSeg.select(when (c.defaultMode) { "SILENT" -> 0; "ON_CALL" -> 1; else -> 2 }, false)
            addr.set(c.addressWindowS.toDouble())
            follow.set(c.followupWindowS.toDouble())
            speedSeg.select(when (c.responseSpeed) { "fast" -> 0; "patient" -> 2; else -> 1 }, false)
            fast.setOnSilently(c.fastMode)
            allowUnknown.setOnSilently(c.allowUnknown)
            intSeg.select(when (c.interruptPolicy) { "never" -> 0; "always" -> 2; else -> 1 }, false)
            yieldS.set(c.yieldChars.toDouble())
            proactive.setOnSilently(c.proactiveAudio)
            affective.setOnSilently(c.affectiveDialog)
            memory.setOnSilently(c.storeMemory)
            screenSeg.select(when (c.screenMode) { "periodic" -> 1; "manual" -> 2; else -> 0 }, false)
            ttl.set(c.verifyTtlS.toDouble())
            frame.set(c.frameIntervalMs.toDouble())
            temp.set(c.temperature)
            filled = true
        }
        showModel()
        renderAccount()
        val has = Secrets.gemini(act).isNotBlank()
        keyC.setSubtitle(if (has) "محفوظ ومشفّر" else "مطلوب لتشغيل الضيف")
        tick()
    }

    override fun tick() {
        var on = 0
        for ((t, g) in perms) {
            val v = g()
            t.setOnSilently(v)
            if (v) on++
        }
        permC.setSubtitle("$on من ${perms.size} مفعّلة")
        val t = AppLog.text(act).ifBlank { "لا يوجد شيء بعد." }
        if (logView.text.toString() != t) logView.text = t
    }

    // ------------------------------------------------------------ test
    private class Line(val ok: Boolean?, val text: String)

    private fun render(lines: List<Line>) {
        results.removeAllViews()
        for (l in lines) {
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            val kind = when (l.ok) { true -> Ic.CHECK; false -> Ic.CROSS; else -> Ic.MINUS }
            val color = when (l.ok) { true -> Ui.GREEN; false -> Ui.RED; else -> Ui.MUTED }
            r.addView(IconView(act, kind, color), LinearLayout.LayoutParams(dp(20), dp(20)))
            r.addView(Ui.text(act, l.text, 12f, Ui.TEXT), Ui.lp(act, 0, Ui.WRAP, 1f, start = 8))
            results.put(r, top = 8)
        }
    }

    private fun testConnections() {
        render(listOf(Line(null, "جارٍ الاختبار...")))
        Thread {
            val out = ArrayList<Line>()
            val gk = Secrets.gemini(act)
            val cfg = Repo.cfg()
            out.add(if (Repo.loaded) Line(true, "الحساب: «${cfg.title}»") else Line(false, "الحساب: ${Repo.error}"))
            if (gk.isNotBlank()) {
                val m = cfg.model.ifBlank { LiveClient.pickModel(gk) ?: "" }
                if (m.isBlank()) out.add(Line(false, "Gemini: المفتاح غير صالح أو لا يوجد نموذج Live"))
                else {
                    Repo.liveModel = m
                    out.add(Line(true, "نموذج Live: $m"))
                    val combos = listOf(
                        Triple(true, true, true), Triple(true, false, true), Triple(false, false, true), Triple(false, false, false)
                    )
                    var done = false
                    var last = ""
                    for (cmb in combos) {
                        val t0 = System.currentTimeMillis()
                        val r = probe(gk, m, cfg, cmb.first, cmb.second, cmb.third)
                        if (r.first) {
                            val ms = System.currentTimeMillis() - t0
                            val feats = listOfNotNull(
                                if (cmb.first) "الصمت الذكي" else null,
                                if (cmb.second) "الحوار العاطفي" else null,
                                if (cmb.third) "وضع السرعة" else null
                            )
                            out.add(Line(true, "جلسة Live تعمل خلال $ms م.ث" + (if (feats.isEmpty()) " (الوضع الأساسي)" else ": " + feats.joinToString("، "))))
                            if (feats.size < 3) out.add(Line(null, "ميزات غير مدعومة في هذا النموذج تُعطَّل تلقائياً"))
                            done = true
                            break
                        } else last = r.second
                    }
                    if (!done) out.add(Line(false, last))
                    val vm = cfg.visionModel.ifBlank { LiveClient.pickTextModel(gk) ?: "" }
                    out.add(Line(null, "نموذج الرؤية: ${if (vm.isBlank()) "غير متاح" else vm}"))
                }
            } else out.add(Line(null, "أضف مفتاح Gemini أولاً"))
            AppLog.add("اختبار الاتصال: " + out.joinToString(" | ") { it.text })
            act.runOnUiThread { render(out) }
        }.start()
    }

    private fun probe(key: String, model: String, cfg: Cfg, extras: Boolean, affective: Boolean, fast: Boolean): Pair<Boolean, String> {
        val latch = CountDownLatch(1)
        var ok = false
        var msg = "Gemini Live: انتهت المهلة بلا رد"
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
                    msg = "Gemini Live أُغلقت ($code): ${reason.take(160)}"
                    latch.countDown()
                }
            }
        })
        client.connect(Prompt.setup(model, cfg, "اختبار", null, extras, false, affective, fast, withTools = false), extras || affective)
        latch.await(12, TimeUnit.SECONDS)
        client.close()
        return ok to msg
    }
}
