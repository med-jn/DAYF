package app.dayf

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
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

/** Identity: pitch black, TikTok cyan #25F4EE and red #FE2C55, white type. One family: IBM Plex Sans Arabic. */
object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    val BG = Color.parseColor("#000000")
    val CARD = Color.parseColor("#121216")
    val CARD2 = Color.parseColor("#1E1E24")
    val LINE = Color.parseColor("#2C2C34")
    val TEXT = Color.parseColor("#FFFFFF")
    val MUTED = Color.parseColor("#8E8F98")
    val CYAN = Color.parseColor("#25F4EE")
    val RED = Color.parseColor("#FE2C55")
    val ACCENT = RED
    val GREEN = Color.parseColor("#3DDC97")
    val AMBER = Color.parseColor("#FFC24B")
    val BLUE = CYAN
    val GRAY = Color.parseColor("#6B6C75")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()
    fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

    // ---------------------------------------------------------------- font (single family)
    private val fonts = HashMap<String, Typeface>()

    private fun tf(c: Context, file: String): Typeface = fonts.getOrPut(file) {
        try {
            Typeface.createFromAsset(c.applicationContext.assets, "fonts/$file")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }

    fun fBody(c: Context): Typeface = tf(c, "Plex-Medium.ttf")
    fun fBold(c: Context): Typeface = tf(c, "Plex-Bold.ttf")

    // ---------------------------------------------------------------- logo assets
    private val bitmaps = HashMap<String, Bitmap?>()

    fun logo(c: Context, name: String): Bitmap? = bitmaps.getOrPut(name) {
        try {
            c.applicationContext.assets.open("logo/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- drawables
    fun shape(c: Context, fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(c, radiusDp).toFloat()
        if (stroke != 0) g.setStroke(dp(c, strokeDp), stroke)
        return g
    }

    private fun ripple(base: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)

    /** Window background: black with faint cyan and red glows (the two halves of the logo). */
    class AppBg : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(c: Canvas) {
            val b = bounds
            p.shader = null
            p.color = BG
            c.drawRect(b, p)
            p.shader = RadialGradient(b.width() * 0.05f, b.height() * 0.02f, b.width() * 0.9f, Color.argb(34, 37, 244, 238), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = RadialGradient(b.width() * 0.98f, b.height() * 0.98f, b.width() * 0.9f, Color.argb(30, 254, 44, 85), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = null
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    // ---------------------------------------------------------------- text
    fun text(c: Context, s: String, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, display: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        t.typeface = if (bold || display) fBold(c) else fBody(c)
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

    /** Main call-to-action: TikTok red. */
    fun primaryBtn(c: Context, label: String, sp: Float = 15f, padV: Int = 13, onClick: () -> Unit): TextView {
        val t = text(c, label, sp, TEXT, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(c, 14), dp(c, padV), dp(c, 14), dp(c, padV))
        t.background = ripple(shape(c, RED, 14))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun ghostBtn(c: Context, label: String, color: Int = TEXT, sp: Float = 13f, padV: Int = 10, onClick: () -> Unit): TextView {
        val t = text(c, label, sp, color, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(c, 12), dp(c, padV), dp(c, 12), dp(c, padV))
        t.background = ripple(shape(c, Color.TRANSPARENT, 14, LINE, 1))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun chip(c: Context, label: String, color: Int): TextView {
        val t = text(c, label, 12f, color, true)
        t.setPadding(dp(c, 11), dp(c, 5), dp(c, 11), dp(c, 5))
        t.background = shape(c, Color.argb(36, Color.red(color), Color.green(color), Color.blue(color)), 20)
        return t
    }

    // ---------------------------------------------------------------- containers
    fun panel(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.background = shape(c, CARD, 18, LINE)
        l.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16))
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

    /** Screen title with the glitch treatment. */
    fun title(c: Context, title: String, sub: String? = null): LinearLayout {
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.addView(GlitchText(c, title, 28f, Gravity.START), LinearLayout.LayoutParams(MATCH, WRAP))
        if (sub != null) box.addView(text(c, sub, 13f, MUTED), lp(c, WRAP, WRAP, top = 2))
        return box
    }

    fun sectionHead(c: Context, s: String): TextView = text(c, s, 16f, TEXT, true)

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

/** Segmented control: selected = white pill, black text. */
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
            tabs[i].background = if (sel) Ui.shape(ctx, Ui.TEXT, 11) else null
            tabs[i].setTextColor(if (sel) Ui.BG else Ui.MUTED)
        }
    }
}
