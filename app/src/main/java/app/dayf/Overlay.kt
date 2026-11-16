package app.dayf

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

class OverlayState(
    val color: Int,
    val stateLabel: String,
    val gate: String,
    val modeIdx: Int,
    val level: Int,
    val footer: String,
    val guestBtn: String,
    val micBtn: String,
    val volume: Int,
    val latency: String
)

private class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

/**
 * Floating control over TikTok. Collapsed: the logo with a state ring that snaps to the screen edge and dims when idle.
 * Expanded: only what is useful during a live.
 */
class Overlay(private val ctx: Context, private val a: Actions) {
    interface Actions {
        fun onStopSpeaking()
        fun onMode(m: SpeakMode)
        fun onCycleGuest()
        fun onCycleMic()
        fun onCommand(text: String)
        fun onVolume(level: Float)
        fun onVerify()
        fun onShowScreen()
        fun onReport()
        fun onCopyLog()
        fun onExit()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val root = LinearLayout(ctx)
    private val bubble = ImageView(ctx)
    private val panel = LinearLayout(ctx)
    private val dot = View(ctx)
    private val stateText = Ui.text(ctx, "", 14f, Ui.TEXT, true)
    private val gateText = Ui.text(ctx, "", 12f, Ui.MUTED)
    private val latencyText = Ui.text(ctx, "", 11f, Ui.CYAN, true)
    private val errorText = Ui.text(ctx, "", 12f, Ui.RED, true)
    private val footer = Ui.text(ctx, "", 11f, Ui.MUTED)
    private val vuFill = View(ctx)
    private val vuRest = View(ctx)
    private val seg = ArrayList<TextView>()
    private val guestChip = Ui.text(ctx, "", 12f, Ui.TEXT, true)
    private val micChip = Ui.text(ctx, "", 12f, Ui.TEXT, true)
    private val commandsBox = FlowLayout(ctx)
    private val volume = SeekBar(ctx)
    private var dragging = false
    private lateinit var scroll: ScrollView
    private lateinit var lp: WindowManager.LayoutParams
    private var expanded = false
    private var shown = false

    private fun px(v: Int) = Ui.dp(ctx, v)

    private fun screen(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun setRing(color: Int) {
        bubble.background = Ui.shape(ctx, Ui.CARD, 28, color, 3)
        dot.background = Ui.shape(ctx, color, 6)
    }

    private val dim = Runnable {
        if (!expanded) root.animate().alpha(0.5f).setDuration(300).start()
    }

    private fun wake() {
        root.animate().cancel()
        root.alpha = 1f
        handler.removeCallbacks(dim)
        if (!expanded) handler.postDelayed(dim, 4000)
    }

    private fun iconBtn(kind: Ic, tint: Int, onClick: () -> Unit): IconView {
        val v = IconView(ctx, kind, tint)
        v.isClickable = true
        v.setOnClickListener { onClick() }
        return v
    }

    fun show() {
        if (shown) return
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        bubble.setImageBitmap(Ui.logo(ctx, "mark"))
        bubble.scaleType = ImageView.ScaleType.FIT_CENTER
        bubble.setPadding(px(13), px(10), px(13), px(10))
        setRing(Ui.GRAY)
        root.addView(bubble, LinearLayout.LayoutParams(px(56), px(56)))

        panel.orientation = LinearLayout.VERTICAL
        panel.background = Ui.shape(ctx, 0xF5121216.toInt(), 18, Ui.LINE)
        panel.setPadding(px(14), px(12), px(14), px(14))

        // header
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(dot, LinearLayout.LayoutParams(px(10), px(10)))
        head.addView(Ui.text(ctx, "Tik Guest", 15f, Ui.TEXT, true), Ui.lp(ctx, 0, Ui.WRAP, 1f, start = 8))
        head.addView(latencyText, Ui.lp(ctx, Ui.WRAP, Ui.WRAP, end = 10))
        head.addView(iconBtn(Ic.CROSS, Ui.MUTED) { toggle() }, LinearLayout.LayoutParams(px(22), px(22)))
        panel.addView(head)

        panel.addView(stateText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 10))
        panel.addView(gateText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 2))
        errorText.visibility = View.GONE
        panel.addView(errorText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 6))

        // level meter
        val vu = LinearLayout(ctx)
        vu.orientation = LinearLayout.HORIZONTAL
        vu.background = Ui.shape(ctx, Ui.CARD2, 3)
        vuFill.background = Ui.shape(ctx, Ui.CYAN, 3)
        vu.addView(vuFill, LinearLayout.LayoutParams(0, Ui.MATCH, 0f))
        vu.addView(vuRest, LinearLayout.LayoutParams(0, Ui.MATCH, 100f))
        panel.addView(vu, Ui.lp(ctx, Ui.MATCH, px(5), top = 10))

        // mode
        val segBox = LinearLayout(ctx)
        segBox.orientation = LinearLayout.HORIZONTAL
        segBox.background = Ui.shape(ctx, Ui.BG, 12, Ui.LINE)
        segBox.setPadding(px(3), px(3), px(3), px(3))
        val labels = listOf("صامت", "عند النداء", "ذكي")
        val modes = listOf(SpeakMode.SILENT, SpeakMode.ON_CALL, SpeakMode.AUTO)
        for (i in 0 until 3) {
            val t = Ui.text(ctx, labels[i], 12f, Ui.TEXT, true)
            t.gravity = Gravity.CENTER
            t.setPadding(px(4), px(9), px(4), px(9))
            t.isClickable = true
            t.setOnClickListener { a.onMode(modes[i]); wake() }
            seg.add(t)
            segBox.addView(t, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        panel.addView(segBox, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 12))

        // stop talking
        panel.addView(
            Ui.iconSolid(ctx, Ic.STOP, "اسكت الآن", Ui.RED, Ui.TEXT, 13f, 10) { a.onStopSpeaking(); wake() },
            Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 10)
        )

        // pause / resume of a playing audio clip: visible only while a clip is active
        val pb = Ui.iconSolid(ctx, Ic.PAUSE, "إيقاف الصوت مؤقتاً", Ui.CYAN, Ui.BG, 13f, 10) { MediaOut.toggleByUser(); wake() }
        val rb = Ui.iconSolid(ctx, Ic.PLAY, "متابعة الصوت", Ui.GREEN, Ui.BG, 13f, 10) { MediaOut.toggleByUser(); wake() }
        pauseBtn = pb
        resumeBtn = rb
        panel.addView(pb, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 10))
        panel.addView(rb, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 10))
        MediaOut.listeners.remove(mediaListener)
        MediaOut.listeners.add(mediaListener)
        refreshMedia()

        // quick commands
        commandsBox.layoutDirection = View.LAYOUT_DIRECTION_RTL
        panel.addView(commandsBox, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 10))

        // volume
        val vr = LinearLayout(ctx)
        vr.orientation = LinearLayout.HORIZONTAL
        vr.gravity = Gravity.CENTER_VERTICAL
        vr.addView(IconView(ctx, Ic.VOL_DOWN, Ui.MUTED), LinearLayout.LayoutParams(px(20), px(20)))
        volume.max = 100
        volume.progress = 100
        volume.progressTintList = ColorStateList.valueOf(Ui.CYAN)
        volume.progressBackgroundTintList = ColorStateList.valueOf(Ui.LINE)
        volume.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) a.onVolume(max(5, p) / 100f)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
            }
        })
        vr.addView(volume, Ui.lp(ctx, 0, Ui.WRAP, 1f, start = 6, end = 6))
        vr.addView(IconView(ctx, Ic.VOL_UP, Ui.MUTED), LinearLayout.LayoutParams(px(20), px(20)))
        panel.addView(vr, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 12))

        // my state: what the app believes about me, with manual override
        val sr = LinearLayout(ctx)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        for (t in listOf(guestChip, micChip)) {
            t.gravity = Gravity.CENTER
            t.setPadding(px(6), px(9), px(6), px(9))
            t.background = Ui.shape(ctx, Ui.CARD2, 10, Ui.LINE)
            t.isClickable = true
        }
        guestChip.setOnClickListener { a.onCycleGuest(); wake() }
        micChip.setOnClickListener { a.onCycleMic(); wake() }
        sr.addView(guestChip, Ui.lp(ctx, 0, Ui.WRAP, 1f, end = 4))
        sr.addView(micChip, Ui.lp(ctx, 0, Ui.WRAP, 1f, start = 4, end = 6))
        sr.addView(iconBtn(Ic.REFRESH, Ui.CYAN) { a.onVerify(); wake() }, Ui.lp(ctx, px(26), px(26), end = 8))
        sr.addView(iconBtn(Ic.EYE, Ui.CYAN) { a.onShowScreen(); wake() }, LinearLayout.LayoutParams(px(26), px(26)))
        panel.addView(sr, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 10))

        footer.maxWidth = px(270)
        panel.addView(footer, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 10))

        // utilities
        val ur = LinearLayout(ctx)
        ur.orientation = LinearLayout.HORIZONTAL
        ur.addView(Ui.iconGhost(ctx, Ic.FLAG, "إبلاغ", Ui.TEXT, 11f, 8) { a.onReport() }, Ui.lp(ctx, 0, Ui.WRAP, 1f, end = 4))
        ur.addView(Ui.iconGhost(ctx, Ic.COPY, "السجل", Ui.TEXT, 11f, 8) { a.onCopyLog() }, Ui.lp(ctx, 0, Ui.WRAP, 1f, start = 4, end = 4))
        ur.addView(Ui.iconGhost(ctx, Ic.LOGOUT, "إنهاء", Ui.RED, 11f, 8) { a.onExit() }, Ui.lp(ctx, 0, Ui.WRAP, 1f, start = 4))
        panel.addView(ur, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, top = 10))

        val dm = screen()
        scroll = MaxHeightScroll(ctx, (dm.heightPixels * 0.78f).toInt())
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(panel)
        scroll.visibility = View.GONE
        root.addView(scroll, Ui.lp(ctx, px(290), Ui.WRAP, top = 6))

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
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
                        wake()
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
                        if (!moved) toggle() else snap()
                        wake()
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(root, lp)
        shown = true
        applyMode(1)
        wake()
    }

    /** Stick to the nearest screen edge so it never covers the live. */
    private fun snap() {
        if (expanded) return
        val sw = screen().widthPixels
        val target = if (lp.x + px(28) < sw / 2) 0 else sw - px(56)
        val anim = ValueAnimator.ofInt(lp.x, target)
        anim.duration = 180
        anim.addUpdateListener {
            lp.x = it.animatedValue as Int
            try {
                wm.updateViewLayout(root, lp)
            } catch (e: Exception) {
            }
        }
        anim.start()
    }

    private fun toggle() {
        expanded = !expanded
        scroll.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) {
            lp.x = lp.x.coerceIn(0, max(0, screen().widthPixels - px(290)))
            wm.updateViewLayout(root, lp)
            root.post {
                val maxY = screen().heightPixels - root.height - px(24)
                if (lp.y > maxY) {
                    lp.y = max(0, maxY)
                    try {
                        wm.updateViewLayout(root, lp)
                    } catch (e: Exception) {
                    }
                }
            }
        } else snap()
        wake()
    }

    fun setCommands(list: List<String>) {
        commandsBox.removeAllViews()
        for (c in list.take(4)) {
            val t = Ui.text(ctx, c, 12f, Ui.TEXT, true)
            t.setPadding(px(11), px(8), px(11), px(8))
            t.background = Ui.shape(ctx, Ui.CARD2, 16, Ui.LINE)
            t.isClickable = true
            t.setOnClickListener { a.onCommand(c); wake() }
            commandsBox.addView(t)
        }
        commandsBox.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setError(msg: String) {
        if (!shown) return
        errorText.text = msg
        errorText.visibility = View.VISIBLE
        setRing(Ui.RED)
        if (!expanded) toggle()
    }

    private fun applyMode(idx: Int) {
        for (i in 0 until seg.size) {
            val sel = i == idx
            seg[i].background = if (sel) Ui.shape(ctx, Ui.CYAN, 9) else null
            seg[i].setTextColor(if (sel) Ui.BG else Ui.MUTED)
        }
    }

    private var pauseBtn: View? = null
    private var resumeBtn: View? = null
    private val mediaListener: () -> Unit = { android.os.Handler(android.os.Looper.getMainLooper()).post { refreshMedia() } }

    private fun refreshMedia() {
        pauseBtn?.visibility = if (MediaOut.playing) View.VISIBLE else View.GONE
        resumeBtn?.visibility = if (MediaOut.paused) View.VISIBLE else View.GONE
    }

    fun update(s: OverlayState) {
        if (!shown) return
        if (errorText.visibility != View.VISIBLE) setRing(s.color)
        stateText.text = s.stateLabel
        gateText.text = s.gate
        latencyText.text = s.latency
        footer.text = s.footer
        guestChip.text = s.guestBtn
        micChip.text = s.micBtn
        applyMode(s.modeIdx)
        if (!dragging) volume.progress = s.volume.coerceIn(5, 100)
        val lvl = s.level.coerceIn(0, 100)
        (vuFill.layoutParams as LinearLayout.LayoutParams).weight = lvl.toFloat()
        (vuRest.layoutParams as LinearLayout.LayoutParams).weight = (100 - lvl).toFloat()
        vuFill.parent?.requestLayout()
    }

    fun hide() {
        if (!shown) return
        MediaOut.listeners.remove(mediaListener)
        shown = false
        handler.removeCallbacksAndMessages(null)
        try {
            wm.removeView(root)
        } catch (e: Exception) {
        }
    }
}
