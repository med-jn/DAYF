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
    private val reqFile = 43
    private var pendingDiag = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var container: FrameLayout
    private lateinit var bar: BottomBar
    private lateinit var screens: List<Screen>
    private var current = 0
    private lateinit var loginView: View
    private var signedUi = false
    private var loading = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppLog.installCrashHandler(this)
        window.setBackgroundDrawable(Ui.AppBg())
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
        loginView = buildLoginView(this)
        setContentView(root)
        applyAuthState()
    }

    /** Shows the login screen when signed out, the app when signed in. */
    fun applyAuthState() {
        if (Auth.isSignedIn(this)) {
            if (!signedUi) {
                signedUi = true
                bar.view.visibility = View.VISIBLE
                go(UiState.tab.coerceIn(0, screens.size - 1))
            }
            if (!Repo.loaded && !loading) {
                loading = true
                Repo.load(this) {
                    loading = false
                    screens.forEach { it.reset() }
                    screens[current].onShow()
                }
            }
        } else {
            signedUi = false
            bar.view.visibility = View.GONE
            container.removeAllViews()
            container.addView(loginView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        }
    }

    fun signOut() {
        Auth.signOut(this)
        stopAgent()
        applyAuthState()
    }

    fun onPersonaChanged() {
        screens.forEach { it.reset() }
        screens[current].onShow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyAuthState()
    }

    fun go(i: Int) {
        current = i
        UiState.tab = i
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
        // a share link opened while the app was closed: offer to add the persona once the user is signed in
        if (Auth.isSignedIn(this)) {
            val pending = Prefs.get(this, "pending_share")
            if (pending.isNotBlank()) {
                Prefs.putNow(this, "pending_share", "")
                window.decorView.post { Sharing.acceptDialog(this, pending) }
            }
        }
        super.onResume()
        applyAuthState()
        if (signedUi) screens[current].onResume()
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

    /** System file picker for sources: one or many files. audioOnly narrows the list to sound files. */
    fun pickFiles(audioOnly: Boolean, cb: (List<android.net.Uri>) -> Unit) {
        UiState.fileCb = cb
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (audioOnly) "audio/*" else "*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqFile)
    }

    fun pickFile(cb: (android.net.Uri) -> Unit) = pickFiles(false) { l -> l.firstOrNull()?.let(cb) }

    fun requestMic() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqPerm)
    }

    fun begin(diag: Boolean) {
        pendingDiag = diag
        if (!diag && Secrets.gemini(this).isBlank()) {
            toast("أضف مفتاح Gemini أولاً")
            go(4)
            return
        }
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
        if (requestCode == reqFile) {
            val uris = ArrayList<android.net.Uri>()
            val cd = data?.clipData
            if (resultCode == RESULT_OK && cd != null) for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            else if (resultCode == RESULT_OK) data?.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) UiState.fileCb?.invoke(uris)
            UiState.fileCb = null
            return
        }
        if (requestCode == Drive.REQ_CODE) {
            Drive.onResult(this, data)
            return
        }
        if (requestCode == reqProj) {
            if (resultCode == RESULT_OK && data != null) {
                AppLog.add("تمت الموافقة. تشغيل الخدمة…")
                val i = Intent(this, AgentService::class.java)
                    .setAction(AgentService.ACTION_START)
                    .putExtra(AgentService.EXTRA_CODE, resultCode)
                    .putExtra(AgentService.EXTRA_DATA, data)
                    .putExtra(AgentService.EXTRA_DIAG, pendingDiag)
                startForegroundService(i)
                toast("بدأ التشغيل. افتح تيك توك وابحث عن الفقاعة العائمة")
                handler.postDelayed({ moveTaskToBack(true) }, 1200)
            } else {
                AppLog.add("لم تُمنح موافقة مشاركة الشاشة")
                toast("لم تُمنح موافقة مشاركة الشاشة")
            }
        }
    }
}
