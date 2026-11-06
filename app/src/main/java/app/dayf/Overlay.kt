package app.dayf

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class Overlay(
    private val ctx: Context,
    private val a: Actions,
    private val commands: List<String>
) {
    interface Actions {
        fun onStopSpeaking()
        fun onMode(m: SpeakMode)
        fun onCycleGuest()
        fun onCycleMic()
        fun onCommand(text: String)
        fun onVolume(delta: Float)
        fun onDump()
        fun onExit()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private val root = LinearLayout(ctx)
    private val bubble = TextView(ctx)
    private val panel = LinearLayout(ctx)
    private val status = TextView(ctx)
    private val guestBtn = Button(ctx)
    private val micBtn = Button(ctx)
    private lateinit var lp: WindowManager.LayoutParams
    private var expanded = false
    private var shown = false

    private fun px(v: Int) = (v * d).toInt()

    private fun round(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = r * d
    }

    private fun btn(b: Button, label: String, onClick: () -> Unit): Button {
        b.text = label
        b.isAllCaps = false
        b.textSize = 12f
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(px(8), px(6), px(8), px(6))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    fun show() {
        if (shown) return
        root.orientation = LinearLayout.VERTICAL

        bubble.text = "🎙"
        bubble.textSize = 20f
        bubble.gravity = Gravity.CENTER
        bubble.setTextColor(Color.WHITE)
        bubble.background = round(Color.GRAY, 24f)
        root.addView(bubble, LinearLayout.LayoutParams(px(48), px(48)))

        panel.orientation = LinearLayout.VERTICAL
        panel.background = round(0xE6111111.toInt(), 12f)
        panel.setPadding(px(10), px(10), px(10), px(10))
        panel.minimumWidth = px(250)
        panel.visibility = View.GONE

        status.setTextColor(Color.WHITE)
        status.textSize = 12f
        status.maxWidth = px(260)
        panel.addView(status)

        panel.addView(row(
            btn(Button(ctx), "⏹ اسكت فوراً") { a.onStopSpeaking() },
            btn(Button(ctx), "🔇 صامت") { a.onMode(SpeakMode.SILENT) }
        ))
        panel.addView(row(
            btn(Button(ctx), "عند النداء") { a.onMode(SpeakMode.ON_CALL) },
            btn(Button(ctx), "تلقائي") { a.onMode(SpeakMode.AUTO) }
        ))
        panel.addView(row(
            btn(guestBtn, "ضيف: تلقائي") { a.onCycleGuest() },
            btn(micBtn, "مايك: تلقائي") { a.onCycleMic() }
        ))
        panel.addView(row(
            btn(Button(ctx), "🔉 −") { a.onVolume(-0.1f) },
            btn(Button(ctx), "🔊 +") { a.onVolume(0.1f) }
        ))
        for (c in commands.take(6)) {
            panel.addView(btn(Button(ctx), c) { a.onCommand(c) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(row(
            btn(Button(ctx), "📋 نسخ الشاشة") { a.onDump() },
            btn(Button(ctx), "✖ إنهاء") { a.onExit() }
        ))
        root.addView(panel)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = px(8)
        lp.y = px(220)

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt()
                        val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > px(6) || abs(dy) > px(6)) moved = true
                        lp.x = sx + dx
                        lp.y = sy + dy
                        wm.updateViewLayout(root, lp)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) toggle()
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(root, lp)
        shown = true
    }

    private fun toggle() {
        expanded = !expanded
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    fun update(text: String, color: Int, guestLabel: String, micLabel: String) {
        if (!shown) return
        status.text = text
        bubble.background = round(color, 24f)
        guestBtn.text = guestLabel
        micBtn.text = micLabel
    }

    fun hide() {
        if (!shown) return
        shown = false
        try {
            wm.removeView(root)
        } catch (e: Exception) {
        }
    }
}
