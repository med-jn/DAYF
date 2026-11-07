package app.dayf

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Visual identity: obsidian + brass. Display face: Reem Kufi. Calligraphy: Aref Ruqaa. Body: Tajawal. */
object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    val BG = Color.parseColor("#0A0910")
    val CARD = Color.parseColor("#13121B")
    val CARD2 = Color.parseColor("#1C1B28")
    val LINE = Color.parseColor("#2B2939")
    val TEXT = Color.parseColor("#F4EEDF")
    val MUTED = Color.parseColor("#A39F94")
    val GOLD = Color.parseColor("#C8A24A")
    val GOLD_L = Color.parseColor("#EBD08A")
    val GOLD_D = Color.parseColor("#8E6F2A")
    val ACCENT = GOLD
    val GREEN = Color.parseColor("#35C79A")
    val RED = Color.parseColor("#D9566B")
    val AMBER = Color.parseColor("#E8B04B")
    val BLUE = Color.parseColor("#5B8DEF")
    val GRAY = Color.parseColor("#6F6B7A")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()
    fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

    // ---------------------------------------------------------------- fonts
    private val fonts = HashMap<String, Typeface>()

    private fun tf(c: Context, file: String): Typeface = fonts.getOrPut(file) {
        try {
            Typeface.createFromAsset(c.applicationContext.assets, "fonts/$file")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }

    fun fBody(c: Context): Typeface = tf(c, "Tajawal-Medium.ttf")
    fun fBold(c: Context): Typeface = tf(c, "Tajawal-Bold.ttf")
    fun fDisplay(c: Context): Typeface = tf(c, "ReemKufi.ttf")
    fun fCalli(c: Context): Typeface = tf(c, "ArefRuqaa-Bold.ttf")

    // ---------------------------------------------------------------- drawables
    fun shape(c: Context, fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(c, radiusDp).toFloat()
        if (stroke != 0) g.setStroke(dp(c, strokeDp), stroke)
        return g
    }

    fun goldFill(c: Context, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(GOLD_L, GOLD))
        g.cornerRadius = dp(c, radiusDp).toFloat()
        return g
    }

    private fun ripple(base: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)

    /** Rounded dark panel with brass corner ticks. */
    class PanelDrawable(private val c: Context) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            fill.color = CARD
            line.color = LINE
            line.style = Paint.Style.STROKE
            line.strokeWidth = dpf(c, 1f)
            tick.color = GOLD
            tick.style = Paint.Style.STROKE
            tick.strokeWidth = dpf(c, 1.6f)
            tick.strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = dpf(c, 18f)
            val rect = RectF(b.left + 1f, b.top + 1f, b.right - 1f, b.bottom - 1f)
            canvas.drawRoundRect(rect, r, r, fill)
            canvas.drawRoundRect(rect, r, r, line)
            val inset = dpf(c, 9f)
            val len = dpf(c, 11f)
            val l = rect.left + inset
            val t = rect.top + inset
            val rr = rect.right - inset
            val bb = rect.bottom - inset
            canvas.drawLine(l, t, l + len, t, tick); canvas.drawLine(l, t, l, t + len, tick)
            canvas.drawLine(rr, t, rr - len, t, tick); canvas.drawLine(rr, t, rr, t + len, tick)
            canvas.drawLine(l, bb, l + len, bb, tick); canvas.drawLine(l, bb, l, bb - len, tick)
            canvas.drawLine(rr, bb, rr - len, bb, tick); canvas.drawLine(rr, bb, rr, bb - len, tick)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---------------------------------------------------------------- text
    fun text(c: Context, s: String, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, display: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        t.typeface = if (display) fDisplay(c) else if (bold) fBold(c) else fBody(c)
        return t
    }

    fun label(c: Context, s: String): TextView = text(c, s, 12f, MUTED)

    // ---------------------------------------------------------------- buttons
    fun btn(
        c: Context, label: String, fill: Int, fg: Int = TEXT, sp: Float = 14f, padV: Int = 12,
        onClick: () -> Unit
    ): TextView {
        val t = text(c, label, sp, fg, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(c, 12), dp(c, padV), dp(c, 12), dp(c, padV))
        t.background = ripple(shape(c, fill, 12))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun goldBtn(c: Context, label: String, sp: Float = 15f, padV: Int = 13, onClick: () -> Unit): TextView {
        val t = text(c, label, sp, BG, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(c, 14), dp(c, padV), dp(c, 14), dp(c, padV))
        t.background = ripple(goldFill(c, 14))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun ghostBtn(c: Context, label: String, color: Int = GOLD_L, sp: Float = 13f, padV: Int = 10, onClick: () -> Unit): TextView {
        val t = text(c, label, sp, color, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(c, 12), dp(c, padV), dp(c, 12), dp(c, padV))
        t.background = ripple(shape(c, Color.TRANSPARENT, 14, GOLD_D, 1))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun chip(c: Context, label: String, color: Int): TextView {
        val t = text(c, label, 12f, color, true)
        t.setPadding(dp(c, 11), dp(c, 5), dp(c, 11), dp(c, 5))
        t.background = shape(c, Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), 20)
        return t
    }

    // ---------------------------------------------------------------- containers
    fun panel(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.background = PanelDrawable(c)
        l.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18))
        return l
    }

    fun card(c: Context): LinearLayout = panel(c)

    fun lp(
        c: Context, w: Int, h: Int, weight: Float = 0f,
        top: Int = 0, start: Int = 0, end: Int = 0, bottom: Int = 0
    ): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.topMargin = dp(c, top)
        p.bottomMargin = dp(c, bottom)
        p.marginStart = dp(c, start)
        p.marginEnd = dp(c, end)
        return p
    }

    fun title(c: Context, title: String, sub: String? = null): LinearLayout {
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(KhatamView(c, GOLD, false), LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)))
        val t = text(c, title, 26f, TEXT, false, true)
        row.addView(t, lp(c, WRAP, WRAP, start = 10))
        box.addView(row)
        if (sub != null) box.addView(text(c, sub, 13f, MUTED), lp(c, WRAP, WRAP, top = 4))
        return box
    }

    fun sectionHead(c: Context, s: String): TextView = text(c, s, 17f, GOLD_L, false, true)

    fun field(
        c: Context, hint: String, initial: String, multiline: Boolean = false,
        ltr: Boolean = false, secret: Boolean = false
    ): EditText {
        val e = EditText(c)
        e.textSize = 14f
        e.typeface = fBody(c)
        e.setTextColor(TEXT)
        e.setHintTextColor(GRAY)
        e.hint = hint
        e.background = shape(c, BG, 12, LINE)
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        if (multiline) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            e.minLines = 4
            e.gravity = Gravity.TOP or Gravity.START
        } else {
            e.setSingleLine(true)
            e.inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL)
            e.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        if (ltr) {
            e.layoutDirection = View.LAYOUT_DIRECTION_LTR
            e.textDirection = View.TEXT_DIRECTION_LTR
        }
        e.setText(initial)
        return e
    }

    fun LinearLayout.put(
        v: View, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0,
        w: Int = MATCH, h: Int = WRAP, weight: Float = 0f
    ) {
        addView(v, lp(context, w, h, weight, top, start, end, bottom))
    }
}

/** Segmented control, brass highlight. */
class Segmented(val ctx: Context, labels: List<String>, var index: Int, private val onSelect: (Int) -> Unit) {
    val view = LinearLayout(ctx)
    private val tabs = ArrayList<TextView>()

    init {
        view.orientation = LinearLayout.HORIZONTAL
        view.background = Ui.shape(ctx, Ui.BG, 14, Ui.LINE)
        view.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
        for (i in labels.indices) {
            val t = Ui.text(ctx, labels[i], 13f, Ui.MUTED, true)
            t.gravity = Gravity.CENTER
            t.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10))
            t.isClickable = true
            t.setOnClickListener { select(i, true) }
            tabs.add(t)
            view.addView(t, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        paint()
    }

    fun select(i: Int, notify: Boolean) {
        index = i
        paint()
        if (notify) onSelect(i)
    }

    private fun paint() {
        for (i in tabs.indices) {
            val sel = i == index
            tabs[i].background = if (sel) Ui.goldFill(ctx, 11) else null
            tabs[i].setTextColor(if (sel) Ui.BG else Ui.MUTED)
        }
    }
}
