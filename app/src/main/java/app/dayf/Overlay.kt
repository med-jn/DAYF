package app.dayf

import android.content.Context
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class OverlayState(
    val color: Int,
    val stateLabel: String,
    val gate: String,
    val modeIdx: Int,
    val level: Int,
    val footer: String,
    val guestBtn: String,
    val micBtn: String
)

private class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

class Overlay(private val ctx: Context, private val a: Actions) {
    interface Actions {
        fun onStopSpeaking()
        fun onMode(m: SpeakMode)
        fun onCycleGuest()
        fun onCycleMic()
        fun onCommand(text: String)
        fun onVolume(delta: Float)
        fun onDump()
        fun onCopyLog()
        fun onExit()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val root = LinearLayout(ctx)
    private val bubble = TextView(ctx)
    private val panel = LinearLayout(ctx)
    private val stateChip = TextView(ctx)
    private val gateText = TextView(ctx)
    private val errorText = TextView(ctx)
    private val footer = TextView(ctx)
    private val vuFill = View(ctx)
    private val vuRest = View(ctx)
    private val seg = ArrayList<TextView>()
    private val guestChip = TextView(ctx)
    private val micChip = TextView(ctx)
    private val commandsBox = LinearLayout(ctx)
    private lateinit var lp: WindowManager.LayoutParams
    private var expanded = false
    private var shown = false
    private var lastColor = Ui.GRAY

    private fun px(v: Int) = Ui.dp(ctx, v)

    private fun setBubble(color: Int) {
        lastColor = color
        bubble.background = Ui.shape(ctx, Ui.CARD, 28, color, 3)
    }

    fun show() {
        if (shown) return
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        bubble.text = "🎙"
        bubble.textSize = 22f
        bubble.gravity = Gravity.CENTER
        setBubble(Ui.GRAY)
        root.addView(bubble, LinearLayout.LayoutParams(px(56), px(56)))

        panel.orientation = LinearLayout.VERTICAL
        panel.background = Ui.shape(ctx, 0xF2171A21.toInt(), 18, Ui.LINE)
        panel.setPadding(px(14), px(14), px(14), px(14))

        // header
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(ctx, "الضيف", 17f, Ui.TEXT, true), Ui.lp(ctx, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        stateChip.text = "…"
        stateChip.textSize = 12f
        stateChip.setPadding(px(10), px(4), px(10), px(4))
        head.addView(stateChip)
        panel.addView(head)

        gateText.textSize = 13f
        gateText.setTextColor(Ui.MUTED)
        panel.addView(gateText, Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 6))

        errorText.textSize = 13f
        errorText.setTextColor(Ui.RED)
        errorText.visibility = View.GONE
        panel.addView(errorText, Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 6))

        // level meter
        val vu = LinearLayout(ctx)
        vu.orientation = LinearLayout.HORIZONTAL
        vu.background = Ui.shape(ctx, Ui.CARD2, 3)
        vuFill.background = Ui.shape(ctx, Ui.GREEN, 3)
        vu.addView(vuFill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f))
        vu.addView(vuRest, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 100f))
        panel.addView(vu, Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, px(6), top = 10))

        // segmented mode control
        val segBox = LinearLayout(ctx)
        segBox.orientation = LinearLayout.HORIZONTAL
        segBox.background = Ui.shape(ctx, Ui.CARD2, 12)
        segBox.setPadding(px(3), px(3), px(3), px(3))
        val labels = listOf("🔇 صامت", "🎯 عند النداء", "⚡ تلقائي")
        val modes = listOf(SpeakMode.SILENT, SpeakMode.ON_CALL, SpeakMode.AUTO)
        for (i in 0 until 3) {
            val t = Ui.text(ctx, labels[i], 12f, Ui.TEXT, true)
            t.gravity = Gravity.CENTER
            t.setPadding(px(4), px(9), px(4), px(9))
            t.isClickable = true
            t.setOnClickListener { a.onMode(modes[i]) }
            seg.add(t)
            segBox.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel.addView(segBox, Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 10))

        // manual overrides
        val ov = LinearLayout(ctx)
        ov.orientation = LinearLayout.HORIZONTAL
        for (t in listOf(guestChip, micChip)) {
            t.textSize = 12f
            t.setTextColor(Ui.TEXT)
            t.gravity = Gravity.CENTER
            t.setPadding(px(6), px(9), px(6), px(9))
            t.background = Ui.shape(ctx, Ui.CARD2, 10, Ui.LINE)
            t.isClickable = true
        }
        guestChip.setOnClickListener { a.onCycleGuest() }
        micChip.setOnClickListener { a.onCycleMic() }
        ov.addView(guestChip, Ui.lp(ctx, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, end = 4))
        ov.addView(micChip, Ui.lp(ctx, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, start = 4))
        panel.addView(ov, Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 8))

        commandsBox.orientation = LinearLayout.VERTICAL
        panel.addView(commandsBox, Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 4))

        // quick actions
        val r1 = LinearLayout(ctx)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.addView(Ui.btn(ctx, "🔉", Ui.CARD2, sp = 14f, padV = 9) { a.onVolume(-0.1f) }, Ui.lp(ctx, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, end = 4))
        r1.addView(Ui.btn(ctx, "🔊", Ui.CARD2, sp = 14f, padV = 9) { a.onVolume(0.1f) }, Ui.lp(ctx, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, start = 4, end = 4))
        r1.addView(Ui.btn(ctx, "⏹ اسكت", Ui.RED, sp = 13f, padV = 9) { a.onStopSpeaking() }, Ui.lp(ctx, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f, start = 4))
        panel.addView(r1, Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 10))

        val r2 = LinearLayout(ctx)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.addView(Ui.btn(ctx, "📋 الشاشة", Ui.CARD2, sp = 12f, padV = 9) { a.onDump() }, Ui.lp(ctx, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, end = 4))
        r2.addView(Ui.btn(ctx, "📜 السجل", Ui.CARD2, sp = 12f, padV = 9) { a.onCopyLog() }, Ui.lp(ctx, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, start = 4, end = 4))
        r2.addView(Ui.btn(ctx, "✖ إنهاء", Ui.CARD2, Ui.RED, sp = 12f, padV = 9) { a.onExit() }, Ui.lp(ctx, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, start = 4))
        panel.addView(r2, Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 8))

        footer.textSize = 11f
        footer.setTextColor(Ui.MUTED)
        panel.addView(footer, Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 10))

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val scroll = MaxHeightScroll(ctx, (dm.heightPixels * 0.62f).toInt())
        scroll.addView(panel)
        scroll.visibility = View.GONE
        scroll.tag = "panelScroll"
        root.addView(scroll, Ui.lp(ctx, px(300), LinearLayout.LayoutParams.WRAP_CONTENT, top = 6))

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = px(8)
        lp.y = px(200)

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x
                        sy = lp.y
                        tx = e.rawX
                        ty = e.rawY
                        moved = false
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
        applyMode(1)
    }

    private fun panelView(): View? = root.findViewWithTag("panelScroll")

    private fun toggle() {
        expanded = !expanded
        panelView()?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    fun setCommands(list: List<String>) {
        commandsBox.removeAllViews()
        for (c in list.take(5)) {
            commandsBox.addView(
                Ui.btn(ctx, c, Ui.CARD2, Ui.TEXT, 13f, 9) { a.onCommand(c) },
                Ui.lp(ctx, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, top = 6)
            )
        }
    }

    fun setError(msg: String) {
        if (!shown) return
        errorText.text = "⚠ $msg"
        errorText.visibility = View.VISIBLE
        setBubble(Ui.RED)
        if (!expanded) toggle()
    }

    private fun applyMode(idx: Int) {
        for (i in 0 until seg.size) {
            val sel = i == idx
            seg[i].background = if (sel) Ui.shape(ctx, Ui.ACCENT, 9) else null
            seg[i].setTextColor(if (sel) Ui.TEXT else Ui.MUTED)
        }
    }

    fun update(s: OverlayState) {
        if (!shown) return
        if (errorText.visibility != View.VISIBLE) setBubble(s.color)
        stateChip.text = s.stateLabel
        stateChip.setTextColor(s.color)
        stateChip.background = Ui.shape(ctx, android.graphics.Color.argb(40, android.graphics.Color.red(s.color), android.graphics.Color.green(s.color), android.graphics.Color.blue(s.color)), 20)
        gateText.text = s.gate
        footer.text = s.footer
        guestChip.text = s.guestBtn
        micChip.text = s.micBtn
        applyMode(s.modeIdx)
        val lvl = s.level.coerceIn(0, 100)
        (vuFill.layoutParams as LinearLayout.LayoutParams).weight = lvl.toFloat()
        (vuRest.layoutParams as LinearLayout.LayoutParams).weight = (100 - lvl).toFloat()
        vuFill.parent?.requestLayout()
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
