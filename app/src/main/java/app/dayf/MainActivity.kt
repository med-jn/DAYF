package app.dayf

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val reqProj = 41
    private val reqPerm = 42
    private var pendingDiag = false
    private val fields = LinkedHashMap<String, EditText>()
    private val handler = Handler(Looper.getMainLooper())

    private class Row(val icon: TextView, val action: TextView?, val optional: Boolean, val check: () -> Boolean)

    private val rows = ArrayList<Row>()
    private lateinit var pill: TextView
    private lateinit var testResult: TextView
    private lateinit var logView: TextView

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppLog.installCrashHandler(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutDirection = View.LAYOUT_DIRECTION_RTL
        col.setPadding(dp(16), dp(36), dp(16), dp(28))

        // header
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(Ui.text(this, "الضيف", 30f, Ui.TEXT, true))
        titles.addView(Ui.text(this, "وكيل صوتي لبثوث تيك توك", 13f, Ui.MUTED))
        head.addView(titles, Ui.lp(this, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pill = Ui.chip(this, "متوقف", Ui.GRAY)
        head.addView(pill)
        col.addView(head, Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, bottom = 14))

        // readiness
        val ready = Ui.card(this)
        ready.addView(Ui.text(this, "الجاهزية", 16f, Ui.TEXT, true))
        addRow(ready, "الظهور فوق التطبيقات (اللوحة العائمة)", false, { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addRow(ready, "صلاحية الميكروفون (لالتقاط الصوت الداخلي)", false, {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqPerm)
        }
        addRow(ready, "المفاتيح (Gemini و Supabase)", false, {
            Prefs.get(this, "geminiKey").isNotBlank() && Prefs.get(this, "supaUrl").isNotBlank() && Prefs.get(this, "supaKey").isNotBlank()
        }, null)
        addRow(ready, "خدمة الإتاحة (اختيارية: قراءة حالة تيك توك)", true, { a11yEnabled() }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addRow(ready, "استثناء البطارية (يُنصح به)", true, {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        col.addView(ready, Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, bottom = 12))

        // keys
        val keys = Ui.card(this)
        keys.addView(Ui.text(this, "المفاتيح والإعدادات", 16f, Ui.TEXT, true))
        addField(keys, "geminiKey", "مفتاح Gemini API", true)
        addField(keys, "supaUrl", "رابط Supabase", false)
        addField(keys, "supaKey", "مفتاح Supabase العام (anon / publishable)", true)
        addField(keys, "host", "اسم المضيف الحالي (لفصل ذاكرة كل بث)", false)
        addField(keys, "myName", "اسم حسابك الظاهر في تيك توك", false)
        val kb = LinearLayout(this)
        kb.orientation = LinearLayout.HORIZONTAL
        kb.addView(Ui.btn(this, "💾 حفظ", Ui.CARD2) { save(); refresh(); toast("تم الحفظ") }, Ui.lp(this, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, end = 6))
        kb.addView(Ui.btn(this, "🔌 اختبار الاتصال", Ui.ACCENT) { testConnections() }, Ui.lp(this, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f, start = 6))
        keys.addView(kb, Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 14))
        testResult = Ui.text(this, "", 13f, Ui.MUTED)
        keys.addView(testResult, Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 10))
        col.addView(keys, Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, bottom = 12))

        // controls
        col.addView(
            Ui.btn(this, "▶  تشغيل الوكيل", Ui.GREEN, Ui.BG, 17f, 16) { save(); begin(false) },
            Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val cr = LinearLayout(this)
        cr.orientation = LinearLayout.HORIZONTAL
        cr.addView(Ui.btn(this, "🎧 اختبار الالتقاط", Ui.CARD2) { save(); begin(true) }, Ui.lp(this, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, end = 6))
        cr.addView(Ui.btn(this, "■ إيقاف", Ui.RED) {
            startService(Intent(this, AgentService::class.java).setAction(AgentService.ACTION_STOP))
        }, Ui.lp(this, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, start = 6))
        col.addView(cr, Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 10, bottom = 12))

        // log
        val logCard = Ui.card(this)
        val lh = LinearLayout(this)
        lh.orientation = LinearLayout.HORIZONTAL
        lh.gravity = Gravity.CENTER_VERTICAL
        lh.addView(Ui.text(this, "السجل", 16f, Ui.TEXT, true), Ui.lp(this, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        lh.addView(Ui.btn(this, "نسخ", Ui.CARD2, Ui.TEXT, 12f, 6) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text(this)))
            toast("نُسخ السجل")
        }, Ui.lp(this, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, end = 6))
        lh.addView(Ui.btn(this, "مسح", Ui.CARD2, Ui.TEXT, 12f, 6) { AppLog.clear(this); refresh() })
        logCard.addView(lh)
        logView = Ui.text(this, "", 11f, Ui.MUTED)
        logView.typeface = Typeface.MONOSPACE
        logView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        logView.textDirection = View.TEXT_DIRECTION_LTR
        logView.setTextIsSelectable(true)
        logCard.addView(logView, Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 8))
        col.addView(logCard)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Ui.BG)
        sv.addView(col)
        setContentView(sv)
    }

    private fun addRow(card: LinearLayout, label: String, optional: Boolean, check: () -> Boolean, action: (() -> Unit)?) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val icon = Ui.text(this, "•", 18f, Ui.MUTED, true)
        icon.gravity = Gravity.CENTER
        r.addView(icon, LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))
        r.addView(Ui.text(this, label, 13f, Ui.TEXT), Ui.lp(this, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, start = 6, end = 6))
        var btn: TextView? = null
        if (action != null) {
            btn = Ui.btn(this, "تفعيل", Ui.CARD2, Ui.ACCENT, 12f, 6) { action() }
            r.addView(btn)
        }
        card.addView(r, Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 10))
        rows.add(Row(icon, btn, optional, check))
    }

    private fun addField(card: LinearLayout, key: String, label: String, secret: Boolean) {
        card.addView(Ui.text(this, label, 12f, Ui.MUTED), Ui.lp(this, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 12))
        val e = EditText(this)
        e.setSingleLine(true)
        e.textSize = 14f
        e.setTextColor(Ui.TEXT)
        e.setHintTextColor(Ui.MUTED)
        e.layoutDirection = View.LAYOUT_DIRECTION_LTR
        e.textDirection = View.TEXT_DIRECTION_LTR
        e.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        e.background = Ui.shape(this, Ui.BG, 10, Ui.LINE)
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        e.inputType = InputType.TYPE_CLASS_TEXT or
            (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
        e.setText(Prefs.get(this, key))
        card.addView(e, Ui.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 4))
        fields[key] = e
    }

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(packageName)
    }

    private fun save() {
        for ((k, e) in fields) Prefs.put(this, k, e.text.toString().trim())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        save()
        super.onPause()
    }

    private fun refresh() {
        for (r in rows) {
            val ok = try {
                r.check()
            } catch (e: Exception) {
                false
            }
            r.icon.text = if (ok) "✔" else if (r.optional) "○" else "✘"
            r.icon.setTextColor(if (ok) Ui.GREEN else if (r.optional) Ui.AMBER else Ui.RED)
            r.action?.visibility = if (ok) View.GONE else View.VISIBLE
        }
        val run = AgentService.running
        pill.text = if (run) "يعمل" else "متوقف"
        pill.setTextColor(if (run) Ui.GREEN else Ui.GRAY)
        pill.background = Ui.shape(this, if (run) 0x332ECC8F else 0x336B7280, 20)
        val txt = AppLog.text(this).ifBlank { "لا يوجد شيء بعد." }
        if (logView.text.toString() != txt) logView.text = txt
    }

    // ------------------------------------------------------------ connection test

    private fun testConnections() {
        save()
        testResult.text = "جارٍ الاختبار…"
        Thread {
            val sb = StringBuilder()
            val gk = Prefs.get(this, "geminiKey")
            val su = Prefs.get(this, "supaUrl")
            val sk = Prefs.get(this, "supaKey")
            var cfg = Cfg(JSONObject())
            if (su.isNotBlank() && sk.isNotBlank()) {
                val s = Supa(su, sk)
                val c = s.config()
                if (c != null) {
                    cfg = Cfg(c)
                    sb.append("✔ Supabase: الوكيل «${cfg.name}»، قواعد ${s.rules().size}، معرفة ${s.knowledge().size}\n")
                } else sb.append("✘ Supabase: ${s.lastError}\n")
            } else sb.append("… Supabase: أدخل الرابط والمفتاح\n")
            if (gk.isNotBlank()) {
                val m = cfg.model.ifBlank { LiveClient.pickModel(gk) ?: "" }
                if (m.isBlank()) sb.append("✘ Gemini: المفتاح غير صالح أو لا يوجد نموذج Live\n")
                else {
                    sb.append("✔ نموذج Live: $m\n")
                    sb.append(liveProbe(gk, m, cfg))
                }
            } else sb.append("… Gemini: أدخل المفتاح\n")
            val out = sb.toString().trim()
            AppLog.add("اختبار الاتصال:\n$out")
            runOnUiThread { testResult.text = out }
        }.start()
    }

    private fun liveProbe(key: String, model: String, cfg: Cfg): String {
        val latch = CountDownLatch(1)
        var out = "✘ Gemini Live: انتهت المهلة بلا رد\n"
        val client = LiveClient(key, object : LiveClient.Listener {
            override fun onSetupComplete() {
                out = "✔ جلسة Gemini Live تعمل\n"
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
                    out = "✘ Gemini Live أُغلقت ($code): ${reason.take(180)}\n"
                    latch.countDown()
                }
            }
        })
        client.connect(Prompt.setup(model, cfg, "اختبار", null))
        latch.await(12, TimeUnit.SECONDS)
        client.close()
        return out
    }

    // ------------------------------------------------------------ start flow

    private fun begin(diag: Boolean) {
        pendingDiag = diag
        if (!Settings.canDrawOverlays(this)) {
            toast("فعّل «الظهور فوق التطبيقات» أولاً")
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), reqPerm)
            return
        }
        askProjection()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        refresh()
    }

    private fun askProjection() {
        AppLog.add("طلب موافقة مشاركة الشاشة…")
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProj)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProj) {
            if (resultCode == RESULT_OK && data != null) {
                AppLog.add("تمت الموافقة. تشغيل الخدمة…")
                val i = Intent(this, AgentService::class.java)
                    .setAction(AgentService.ACTION_START)
                    .putExtra(AgentService.EXTRA_CODE, resultCode)
                    .putExtra(AgentService.EXTRA_DATA, data)
                    .putExtra(AgentService.EXTRA_DIAG, pendingDiag)
                startForegroundService(i)
                toast("بدأ التشغيل، افتح تيك توك وابحث عن الفقاعة 🎙")
                handler.postDelayed({ moveTaskToBack(true) }, 1200)
            } else {
                AppLog.add("لم تُمنح موافقة مشاركة الشاشة")
                toast("لم تُمنح موافقة مشاركة الشاشة")
            }
        }
    }
}
