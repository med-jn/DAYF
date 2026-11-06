package app.dayf

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val reqProj = 41
    private val reqPerm = 42
    private var pendingDiag = false
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var log: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(24), dp(16), dp(24))

        val title = TextView(this)
        title.text = "الضيف: وكيل صوتي لبثوث تيك توك"
        title.textSize = 20f
        title.gravity = Gravity.CENTER
        col.addView(title)

        addField(col, "geminiKey", "مفتاح Gemini API", true)
        addField(col, "supaUrl", "Supabase URL (https://xxxx.supabase.co)", false)
        addField(col, "supaKey", "Supabase anon / publishable key", true)
        addField(col, "host", "اسم المضيف الحالي (لفصل الذاكرة لكل بث)", false)
        addField(col, "myName", "اسم حسابك الظاهر في تيك توك (للتعرف عليك في الشاشة)", false)

        addBtn(col, "1) صلاحية الظهور فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addBtn(col, "2) تفعيل خدمة الإتاحة (قراءة نصوص تيك توك)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addBtn(col, "3) استثناء من توفير البطارية") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        addBtn(col, "4) اختبار الاتصال (Supabase + Gemini)") { testConnections() }
        addBtn(col, "5) اختبار التقاط الصوت فقط (بلا Gemini)") { begin(true) }
        addBtn(col, "▶ تشغيل الوكيل") { begin(false) }
        addBtn(col, "■ إيقاف") {
            startService(Intent(this, AgentService::class.java).setAction(AgentService.ACTION_STOP))
        }

        log = TextView(this)
        log.setPadding(0, dp(12), 0, 0)
        log.setTextColor(Color.DKGRAY)
        col.addView(log)

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    private fun addField(col: LinearLayout, key: String, label: String, secret: Boolean) {
        val t = TextView(this)
        t.text = label
        t.setPadding(0, dp(10), 0, 0)
        col.addView(t)
        val e = EditText(this)
        e.setSingleLine(true)
        e.inputType = InputType.TYPE_CLASS_TEXT or
            (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
        e.setText(Prefs.get(this, key))
        col.addView(e)
        fields[key] = e
    }

    private fun addBtn(col: LinearLayout, label: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener {
            save()
            onClick()
        }
        col.addView(b)
    }

    private fun save() {
        for ((k, e) in fields) Prefs.put(this, k, e.text.toString().trim())
    }

    private fun say(s: String) {
        runOnUiThread { log.text = s }
    }

    private fun testConnections() {
        say("جارٍ الاختبار…")
        Thread {
            val sb = StringBuilder()
            val gk = Prefs.get(this, "geminiKey")
            val su = Prefs.get(this, "supaUrl")
            val sk = Prefs.get(this, "supaKey")
            if (su.isNotBlank() && sk.isNotBlank()) {
                val s = Supa(su, sk)
                val c = s.config()
                if (c != null) {
                    val cfg = Cfg(c)
                    sb.append("Supabase ✔ اسم الوكيل: ").append(cfg.name)
                        .append(" | قواعد: ").append(s.rules().size)
                        .append(" | معرفة: ").append(s.knowledge().size).append('\n')
                } else sb.append("Supabase ✘ ").append(s.lastError).append('\n')
            } else sb.append("Supabase: أدخل البيانات\n")
            if (gk.isNotBlank()) {
                val m = LiveClient.pickModel(gk)
                sb.append(if (m != null) "Gemini ✔ نموذج Live: $m" else "Gemini ✘ المفتاح غير صالح أو لا يدعم Live")
            } else sb.append("Gemini: أدخل المفتاح")
            say(sb.toString())
        }.start()
    }

    private fun begin(diag: Boolean) {
        pendingDiag = diag
        if (!Settings.canDrawOverlays(this)) {
            say("فعّل الخطوة 1 أولاً (الظهور فوق التطبيقات).")
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
        if (code == reqPerm) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askProjection()
            else say("صلاحية الميكروفون مطلوبة لالتقاط الصوت الداخلي.")
        }
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProj)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProj) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, AgentService::class.java)
                    .setAction(AgentService.ACTION_START)
                    .putExtra(AgentService.EXTRA_CODE, resultCode)
                    .putExtra(AgentService.EXTRA_DATA, data)
                    .putExtra(AgentService.EXTRA_DIAG, pendingDiag)
                startForegroundService(i)
                say("بدأ التشغيل. افتح تيك توك وستظهر الفقاعة العائمة 🎙")
                moveTaskToBack(true)
            } else say("لم تُمنح موافقة مشاركة الشاشة.")
        }
    }
}
