package app.dayf

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * A small pill drawn over the browser while the user creates a Gemini key. One tap brings the app back to the front,
 * where the copied key is detected and saved. (Android does not let an app in the background read the clipboard,
 * so a single tap after copying is the closest thing to an automatic return.)
 */
object KeyReturn {
    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())

    fun show(ctx: Context) {
        hide(ctx)
        if (!Settings.canDrawOverlays(ctx)) return
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val pill = LinearLayout(app)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.layoutDirection = View.LAYOUT_DIRECTION_RTL
        pill.setPadding(Ui.dp(app, 16), Ui.dp(app, 11), Ui.dp(app, 18), Ui.dp(app, 11))
        pill.background = Ui.shape(app, Ui.CYAN, 28)
        pill.addView(IconView(app, Ic.BACK, Ui.BG), LinearLayout.LayoutParams(Ui.dp(app, 20), Ui.dp(app, 20)))
        pill.addView(Ui.text(app, "بعد نسخ المفتاح: ارجع إلى Tik Guest", 14f, Ui.BG, true), Ui.lp(app, Ui.WRAP, Ui.WRAP, start = 8))
        pill.setOnClickListener {
            hide(app)
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = Ui.dp(app, 90)
        try {
            wm.addView(pill, lp)
            view = pill
            handler.postDelayed({ hide(app) }, 10 * 60 * 1000L)
        } catch (e: Exception) {
        }
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        view = null
        handler.removeCallbacksAndMessages(null)
        try {
            (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
    }
}
