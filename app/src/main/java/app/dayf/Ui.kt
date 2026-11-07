package app.dayf

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG = Color.parseColor("#0E1014")
    val CARD = Color.parseColor("#171A21")
    val CARD2 = Color.parseColor("#232834")
    val LINE = Color.parseColor("#2C313C")
    val TEXT = Color.parseColor("#F2F4F8")
    val MUTED = Color.parseColor("#9AA3B2")
    val ACCENT = Color.parseColor("#7C6CFF")
    val GREEN = Color.parseColor("#2ECC8F")
    val RED = Color.parseColor("#FF5C6C")
    val AMBER = Color.parseColor("#FFC24B")
    val BLUE = Color.parseColor("#4DA3FF")
    val GRAY = Color.parseColor("#6B7280")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun shape(c: Context, fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(c, radiusDp).toFloat()
        if (stroke != 0) g.setStroke(dp(c, strokeDp), stroke)
        return g
    }

    fun text(c: Context, s: String, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun btn(
        c: Context, label: String, fill: Int, fg: Int = TEXT, sp: Float = 14f, padV: Int = 12,
        onClick: () -> Unit
    ): TextView {
        val t = text(c, label, sp, fg, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(c, 12), dp(c, padV), dp(c, 12), dp(c, padV))
        t.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(c, fill, 12), null)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun chip(c: Context, label: String, color: Int): TextView {
        val t = text(c, label, 12f, color, true)
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4))
        t.background = shape(c, Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), 20)
        return t
    }

    fun card(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.background = shape(c, CARD, 16, LINE)
        l.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14))
        return l
    }

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
}
