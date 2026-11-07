package app.dayf

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {
    private val reqProj = 41
    private val reqPerm = 42
    private var pendingDiag = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var container: FrameLayout
    private lateinit var bar: BottomBar
    private lateinit var screens: List<Screen>
    private var current = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppLog.installCrashHandler(this)
        window.setBackgroundDrawable(ArabesqueBg(this))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        screens = listOf(
            HomeScreen(this), PersonaScreen(this), BrainScreen(this), VoiceScreen(this), SettingsScreen(this)
        )
        bar = BottomBar(
            this,
            listOf(
                "الرئيسية" to Ic.HOME, "الشخصية" to Ic.PERSONA, "المعرفة" to Ic.BOOK,
                "الصوت" to Ic.WAVE, "الإعدادات" to Ic.SLIDERS
            )
        ) { go(it) }
        root.addView(bar.view, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        setContentView(root)
        go(0)
        Repo.load(this) { screens[current].onShow() }
    }

    fun go(i: Int) {
        current = i
        container.removeAllViews()
        container.addView(screens[i].root, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        bar.select(i, false)
        screens[i].onShow()
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private val ticker = object : Runnable {
        override fun run() {
            screens[current].tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    fun openStudio() {
        if (AgentService.running) {
            toast("أوقف الوكيل أولاً لاستعمال الاستوديو")
            return
        }
        startActivity(Intent(this, StudioActivity::class.java))
    }

    fun stopAgent() {
        startService(Intent(this, AgentService::class.java).setAction(AgentService.ACTION_STOP))
    }

    fun requestMic() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqPerm)
    }

    fun begin(diag: Boolean) {
        pendingDiag = diag
        if (!Settings.canDrawOverlays(this)) {
            toast("فعّل «الظهور فوق التطبيقات» من الإعدادات")
            go(4)
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), reqPerm)
            return
        }
        AppLog.add("طلب موافقة مشاركة الشاشة…")
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProj)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        screens[current].tick()
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
