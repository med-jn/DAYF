package app.dayf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Text with the TikTok chromatic split: cyan to the left, red to the right, white on top. */
class GlitchText(ctx: Context, private val txt: String, private val sp: Float, private val gravity: Int) : View(ctx) {
    private fun mk(color: Int): TextPaint {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Ui.fBold(context)
        @Suppress("DEPRECATION")
        p.textSize = sp * context.resources.displayMetrics.scaledDensity
        p.color = color
        return p
    }

    private val pc = mk(Ui.CYAN)
    private val pr = mk(Ui.RED)
    private val pw = mk(Ui.TEXT)
    private var lc: StaticLayout? = null
    private var lr: StaticLayout? = null
    private var lw: StaticLayout? = null

    private fun layoutFor(p: TextPaint, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(txt, 0, txt.length, p, w)
            .setAlignment(if (gravity == Gravity.CENTER_HORIZONTAL) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.ANYRTL_LTR)
            .build()

    override fun onMeasure(ws: Int, hs: Int) {
        var w = MeasureSpec.getSize(ws)
        if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED || w <= 0) w = Ui.dp(context, 320)
        val pad = Ui.dp(context, 4)
        val inner = w - pad * 2
        lc = layoutFor(pc, inner)
        lr = layoutFor(pr, inner)
        lw = layoutFor(pw, inner)
        setMeasuredDimension(w, (lw?.height ?: 0) + pad * 2)
    }

    override fun onDraw(c: Canvas) {
        val pad = Ui.dpf(context, 4f).toInt()
        val o = Ui.dpf(context, 1.6f)
        c.save(); c.translate(pad - o, pad.toFloat()); lc?.draw(c); c.restore()
        c.save(); c.translate(pad + o, pad.toFloat()); lr?.draw(c); c.restore()
        c.save(); c.translate(pad.toFloat(), pad.toFloat()); lw?.draw(c); c.restore()
    }
}

/** Live level meter, cyan to red. Animates itself while attached. */
class WaveView(ctx: Context) : View(ctx) {
    var active = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = 33
    private val hs = FloatArray(n)
    private var t = 0f

    private fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val hh = height.toFloat()
        val level = (StateBus.captureLevel * 3 / 100f).coerceIn(0f, 1f)
        val speaking = StateBus.speaking
        val amp = if (!active) 0.05f else maxOf(level, if (speaking) 0.6f else 0.08f)
        t += 0.08f
        val gap = w / n
        val bw = gap * 0.46f
        for (i in 0 until n) {
            val env = 1f - abs(i - (n - 1) / 2f) / ((n - 1) / 2f) * 0.6f
            val wob = 0.5f + 0.5f * sin(t * 2f + i * 0.6f)
            val target = (0.08f + amp * env * (0.35f + 0.65f * wob)).coerceIn(0.04f, 1f)
            hs[i] += (target - hs[i]) * 0.25f
            val bh = hs[i] * hh
            val x = i * gap + gap / 2
            p.color = if (active) mix(Ui.CYAN, Ui.RED, i / (n - 1f)) else Ui.LINE
            p.alpha = if (active) (110 + 145 * env).toInt() else 255
            c.drawRoundRect(x - bw / 2, (hh - bh) / 2, x + bw / 2, (hh + bh) / 2, bw / 2, bw / 2, p)
        }
        postInvalidateOnAnimation()
    }
}

private fun glitchDraw(c: Canvas, p: Paint, d: Float, draw: (Canvas, Paint) -> Unit) {
    val keep = p.color
    p.color = Ui.CYAN; c.save(); c.translate(-d, 0f); draw(c, p); c.restore()
    p.color = Ui.RED; c.save(); c.translate(d, 0f); draw(c, p); c.restore()
    p.color = Ui.TEXT; draw(c, p)
    p.color = keep
}

/** The big start/stop control, drawn in the logo's chromatic style. */
class PowerButton(ctx: Context) : View(ctx) {
    var running = false
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f

    init {
        isClickable = true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val r = min(w, h) / 2 - Ui.dpf(context, 6f)
        val d = Ui.dpf(context, 2.2f)

        if (running) {
            t += 0.014f
            val ph = t % 1f
            p.style = Paint.Style.STROKE
            p.strokeWidth = Ui.dpf(context, 2f)
            p.color = Ui.CYAN
            p.alpha = ((1f - ph) * 150).toInt()
            c.drawCircle(cx - d, cy, r * (0.78f + 0.22f * ph), p)
            p.color = Ui.RED
            p.alpha = ((1f - ph) * 150).toInt()
            c.drawCircle(cx + d, cy, r * (0.78f + 0.22f * ph), p)
        }
        // disc
        p.style = Paint.Style.FILL
        p.alpha = 255
        p.color = Ui.CARD
        c.drawCircle(cx, cy, r * 0.78f, p)
        // chromatic rim
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.dpf(context, 3f)
        glitchDraw(c, p, d) { cv, pp -> cv.drawCircle(cx, cy, r * 0.78f, pp) }
        // glyph
        p.style = Paint.Style.FILL
        if (running) {
            val s = r * 0.24f
            glitchDraw(c, p, d) { cv, pp -> cv.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.3f, s * 0.3f, pp) }
        } else {
            val s = r * 0.28f
            val path = Path()
            path.moveTo(cx - s * 0.7f, cy - s)
            path.lineTo(cx + s * 1.05f, cy)
            path.lineTo(cx - s * 0.7f, cy + s)
            path.close()
            glitchDraw(c, p, d) { cv, pp -> cv.drawPath(path, pp) }
        }
        if (running) postInvalidateOnAnimation()
    }
}

enum class Ic {
    HOME, PERSONA, BOOK, WAVE, SLIDERS, PLAY, PLUS, TRASH, EDIT, MIC, BACK, STUDIO, CHEVRON,
    STOP, MUTE, VOL_UP, VOL_DOWN, TARGET, SPARK, REFRESH, EYE, FLAG, COPY, HEADPHONES, PLUG,
    CHECK, CROSS, WARN, LINK, FILE, SEARCH, LOGOUT, DATABASE, MINUS, FOLDER, PAUSE
}

private fun speaker(p: Path) {
    p.moveTo(4f, 9f); p.lineTo(8f, 9f); p.lineTo(13f, 5f); p.lineTo(13f, 19f); p.lineTo(8f, 15f); p.lineTo(4f, 15f); p.close()
}

/** Hand-drawn line icons on a 24-unit grid, one consistent stroke style for the whole app. */
fun drawIcon(c: Canvas, kind: Ic, color: Int, width: Int, height: Int, p: Paint) {
    val s = min(width, height) / 24f
    c.save()
    c.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
    c.scale(s, s)
    p.style = Paint.Style.STROKE
    p.shader = null
    p.color = color
    p.strokeWidth = 1.9f
    p.strokeCap = Paint.Cap.ROUND
    p.strokeJoin = Paint.Join.ROUND
    val path = Path()
    when (kind) {
        Ic.HOME -> {
            path.moveTo(3f, 11f); path.lineTo(12f, 3.5f); path.lineTo(21f, 11f)
            path.moveTo(5.5f, 9.5f); path.lineTo(5.5f, 20.5f); path.lineTo(18.5f, 20.5f); path.lineTo(18.5f, 9.5f)
            path.moveTo(10f, 20.5f); path.lineTo(10f, 14f); path.lineTo(14f, 14f); path.lineTo(14f, 20.5f)
            c.drawPath(path, p)
        }
        Ic.PERSONA -> {
            c.drawCircle(12f, 8f, 4f, p)
            path.moveTo(4f, 21f)
            path.cubicTo(4f, 16f, 8f, 14f, 12f, 14f)
            path.cubicTo(16f, 14f, 20f, 16f, 20f, 21f)
            c.drawPath(path, p)
        }
        Ic.BOOK -> {
            path.moveTo(12f, 6f); path.cubicTo(10f, 4.5f, 7f, 4.5f, 3.5f, 5f); path.lineTo(3.5f, 19f)
            path.cubicTo(7f, 18.5f, 10f, 18.7f, 12f, 20.2f)
            path.cubicTo(14f, 18.7f, 17f, 18.5f, 20.5f, 19f); path.lineTo(20.5f, 5f)
            path.cubicTo(17f, 4.5f, 14f, 4.5f, 12f, 6f)
            path.moveTo(12f, 6f); path.lineTo(12f, 20.2f)
            c.drawPath(path, p)
        }
        Ic.WAVE -> {
            val xs = floatArrayOf(4f, 8f, 12f, 16f, 20f)
            val hs = floatArrayOf(2f, 5f, 8f, 4.5f, 1.5f)
            for (i in xs.indices) c.drawLine(xs[i], 12f - hs[i], xs[i], 12f + hs[i], p)
        }
        Ic.SLIDERS -> {
            c.drawLine(3f, 6f, 21f, 6f, p); c.drawLine(3f, 12f, 21f, 12f, p); c.drawLine(3f, 18f, 21f, 18f, p)
            p.style = Paint.Style.FILL
            p.color = Ui.BG
            c.drawCircle(8f, 6f, 2.4f, p); c.drawCircle(16f, 12f, 2.4f, p); c.drawCircle(11f, 18f, 2.4f, p)
            p.style = Paint.Style.STROKE
            p.color = color
            c.drawCircle(8f, 6f, 2.4f, p); c.drawCircle(16f, 12f, 2.4f, p); c.drawCircle(11f, 18f, 2.4f, p)
        }
        Ic.PAUSE -> {
            p.style = Paint.Style.FILL
            c.drawRect(RectF(6f, 5f, 10f, 19f), p)
            c.drawRect(RectF(14f, 5f, 18f, 19f), p)
        }
        Ic.PLAY -> {
            p.style = Paint.Style.FILL
            path.moveTo(7f, 4.5f); path.lineTo(19f, 12f); path.lineTo(7f, 19.5f); path.close()
            c.drawPath(path, p)
        }
        Ic.PLUS -> { c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p) }
        Ic.MINUS -> c.drawLine(6f, 12f, 18f, 12f, p)
        Ic.TRASH -> {
            c.drawLine(4f, 7f, 20f, 7f, p)
            path.moveTo(9f, 7f); path.lineTo(9f, 4f); path.lineTo(15f, 4f); path.lineTo(15f, 7f)
            path.moveTo(6f, 7f); path.lineTo(7f, 20f); path.lineTo(17f, 20f); path.lineTo(18f, 7f)
            path.moveTo(10f, 11f); path.lineTo(10f, 16f); path.moveTo(14f, 11f); path.lineTo(14f, 16f)
            c.drawPath(path, p)
        }
        Ic.EDIT -> {
            path.moveTo(4f, 20f); path.lineTo(8.5f, 19f); path.lineTo(19.5f, 8f); path.lineTo(16f, 4.5f); path.lineTo(5f, 15.5f); path.close()
            path.moveTo(14f, 6.5f); path.lineTo(17.5f, 10f)
            c.drawPath(path, p)
        }
        Ic.MIC -> {
            c.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, p)
            path.moveTo(6f, 11f); path.cubicTo(6f, 15f, 8.5f, 17f, 12f, 17f); path.cubicTo(15.5f, 17f, 18f, 15f, 18f, 11f)
            path.moveTo(12f, 17f); path.lineTo(12f, 21f); path.moveTo(9f, 21f); path.lineTo(15f, 21f)
            c.drawPath(path, p)
        }
        Ic.BACK -> { path.moveTo(9f, 5f); path.lineTo(16f, 12f); path.lineTo(9f, 19f); c.drawPath(path, p) }
        Ic.STUDIO -> {
            c.drawRoundRect(RectF(3f, 6f, 21f, 18f), 3f, 3f, p)
            p.style = Paint.Style.FILL
            path.moveTo(10.5f, 9.5f); path.lineTo(14.5f, 12f); path.lineTo(10.5f, 14.5f); path.close()
            c.drawPath(path, p)
        }
        Ic.CHEVRON -> { path.moveTo(6f, 9f); path.lineTo(12f, 15f); path.lineTo(18f, 9f); c.drawPath(path, p) }
        Ic.STOP -> {
            p.style = Paint.Style.FILL
            c.drawRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 2.2f, 2.2f, p)
        }
        Ic.MUTE -> {
            speaker(path); c.drawPath(path, p)
            c.drawLine(16.5f, 9.5f, 21f, 14.5f, p); c.drawLine(21f, 9.5f, 16.5f, 14.5f, p)
        }
        Ic.VOL_UP -> {
            speaker(path); c.drawPath(path, p)
            val a = Path(); a.moveTo(16f, 9.2f); a.cubicTo(17.6f, 10.8f, 17.6f, 13.2f, 16f, 14.8f)
            a.moveTo(18.6f, 6.6f); a.cubicTo(21.8f, 9.8f, 21.8f, 14.2f, 18.6f, 17.4f)
            c.drawPath(a, p)
        }
        Ic.VOL_DOWN -> {
            speaker(path); c.drawPath(path, p)
            val a = Path(); a.moveTo(16.5f, 9.2f); a.cubicTo(18.1f, 10.8f, 18.1f, 13.2f, 16.5f, 14.8f)
            c.drawPath(a, p)
        }
        Ic.TARGET -> {
            c.drawCircle(12f, 12f, 8f, p)
            p.style = Paint.Style.FILL
            c.drawCircle(12f, 12f, 3.2f, p)
        }
        Ic.SPARK -> {
            path.moveTo(12f, 3f); path.cubicTo(12.6f, 8.4f, 15.6f, 11.4f, 21f, 12f)
            path.cubicTo(15.6f, 12.6f, 12.6f, 15.6f, 12f, 21f)
            path.cubicTo(11.4f, 15.6f, 8.4f, 12.6f, 3f, 12f)
            path.cubicTo(8.4f, 11.4f, 11.4f, 8.4f, 12f, 3f)
            c.drawPath(path, p)
        }
        Ic.REFRESH -> {
            val r = 7f
            val start = -40f
            val sweep = 290f
            path.addArc(RectF(12f - r, 12f - r, 12f + r, 12f + r), start, sweep)
            c.drawPath(path, p)
            val th = Math.toRadians((start + sweep).toDouble())
            val ex = 12f + r * cos(th).toFloat()
            val ey = 12f + r * sin(th).toFloat()
            val tx = (-sin(th)).toFloat()
            val ty = cos(th).toFloat()
            val ah = Path()
            ah.moveTo(ex + tx * 3.2f, ey + ty * 3.2f)
            ah.lineTo(ex - ty * 2.6f, ey + tx * 2.6f)
            ah.lineTo(ex + ty * 2.6f, ey - tx * 2.6f)
            ah.close()
            p.style = Paint.Style.FILL
            c.drawPath(ah, p)
        }
        Ic.EYE -> {
            path.moveTo(2f, 12f); path.cubicTo(5f, 6.5f, 8.5f, 5f, 12f, 5f); path.cubicTo(15.5f, 5f, 19f, 6.5f, 22f, 12f)
            path.cubicTo(19f, 17.5f, 15.5f, 19f, 12f, 19f); path.cubicTo(8.5f, 19f, 5f, 17.5f, 2f, 12f); path.close()
            c.drawPath(path, p)
            c.drawCircle(12f, 12f, 3f, p)
        }
        Ic.FLAG -> {
            c.drawLine(6f, 3f, 6f, 21f, p)
            path.moveTo(6f, 4f); path.lineTo(18f, 4f); path.lineTo(15f, 8.5f); path.lineTo(18f, 13f); path.lineTo(6f, 13f)
            c.drawPath(path, p)
        }
        Ic.COPY -> {
            c.drawRoundRect(RectF(8f, 8f, 20f, 20f), 2.5f, 2.5f, p)
            path.moveTo(16f, 8f); path.lineTo(16f, 6f); path.lineTo(6f, 6f); path.lineTo(6f, 16f); path.lineTo(8f, 16f)
            c.drawPath(path, p)
        }
        Ic.HEADPHONES -> {
            path.moveTo(4f, 15f); path.lineTo(4f, 12f); path.cubicTo(4f, 7.5f, 7.5f, 4f, 12f, 4f)
            path.cubicTo(16.5f, 4f, 20f, 7.5f, 20f, 12f); path.lineTo(20f, 15f)
            c.drawPath(path, p)
            c.drawRoundRect(RectF(3f, 14f, 7f, 20f), 1.6f, 1.6f, p)
            c.drawRoundRect(RectF(17f, 14f, 21f, 20f), 1.6f, 1.6f, p)
        }
        Ic.PLUG -> {
            c.drawLine(9f, 3f, 9f, 8f, p); c.drawLine(15f, 3f, 15f, 8f, p)
            path.moveTo(6f, 8f); path.lineTo(18f, 8f); path.lineTo(18f, 11f)
            path.cubicTo(18f, 14.3f, 15.3f, 17f, 12f, 17f); path.cubicTo(8.7f, 17f, 6f, 14.3f, 6f, 11f); path.close()
            c.drawPath(path, p)
            c.drawLine(12f, 17f, 12f, 21f, p)
        }
        Ic.CHECK -> { path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f); c.drawPath(path, p) }
        Ic.CROSS -> { c.drawLine(6f, 6f, 18f, 18f, p); c.drawLine(18f, 6f, 6f, 18f, p) }
        Ic.WARN -> {
            path.moveTo(12f, 4f); path.lineTo(21f, 20f); path.lineTo(3f, 20f); path.close()
            c.drawPath(path, p)
            c.drawLine(12f, 10f, 12f, 14f, p)
            c.drawPoint(12f, 17f, p)
        }
        Ic.LINK -> {
            c.save()
            c.rotate(-45f, 12f, 12f)
            c.drawRoundRect(RectF(2f, 9f, 13f, 15f), 3f, 3f, p)
            c.drawRoundRect(RectF(11f, 9f, 22f, 15f), 3f, 3f, p)
            c.restore()
        }
        Ic.FILE -> {
            path.moveTo(7f, 3f); path.lineTo(14f, 3f); path.lineTo(19f, 8f); path.lineTo(19f, 21f); path.lineTo(7f, 21f); path.close()
            path.moveTo(14f, 3f); path.lineTo(14f, 8f); path.lineTo(19f, 8f)
            c.drawPath(path, p)
            c.drawLine(10f, 13f, 16f, 13f, p); c.drawLine(10f, 17f, 16f, 17f, p)
        }
        Ic.FOLDER -> {
            path.moveTo(3f, 6f); path.lineTo(9.5f, 6f); path.lineTo(12f, 9f); path.lineTo(21f, 9f)
            path.lineTo(21f, 19f); path.lineTo(3f, 19f); path.close()
            c.drawPath(path, p)
        }
        Ic.SEARCH -> {
            c.drawCircle(10f, 10f, 6f, p)
            c.drawLine(14.5f, 14.5f, 20f, 20f, p)
        }
        Ic.LOGOUT -> {
            path.moveTo(10f, 4f); path.lineTo(5f, 4f); path.lineTo(5f, 20f); path.lineTo(10f, 20f)
            path.moveTo(14f, 8f); path.lineTo(19f, 12f); path.lineTo(14f, 16f)
            c.drawPath(path, p)
            c.drawLine(19f, 12f, 9f, 12f, p)
        }
        Ic.DATABASE -> {
            c.drawOval(RectF(5f, 4f, 19f, 9f), p)
            path.moveTo(5f, 6.5f); path.lineTo(5f, 17.5f); path.moveTo(19f, 6.5f); path.lineTo(19f, 17.5f)
            path.moveTo(5f, 17.5f); path.cubicTo(5f, 19f, 8f, 20.5f, 12f, 20.5f); path.cubicTo(16f, 20.5f, 19f, 19f, 19f, 17.5f)
            path.moveTo(5f, 12f); path.cubicTo(5f, 13.5f, 8f, 15f, 12f, 15f); path.cubicTo(16f, 15f, 19f, 13.5f, 19f, 12f)
            c.drawPath(path, p)
        }
    }
    c.restore()
}

class IconView(ctx: Context, kind: Ic, var color: Int = Ui.TEXT) : View(ctx) {
    var kind: Ic = kind
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun tint(c: Int) {
        color = c
        invalidate()
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val d = Ui.dp(context, 22)
        setMeasuredDimension(resolveSize(d, ws), resolveSize(d, hs))
    }

    override fun onDraw(c: Canvas) {
        drawIcon(c, kind, color, width, height, p)
    }
}

/** A button made of an icon and a label, used instead of emoji everywhere. */
class IconButton(
    ctx: Context, icon: Ic, label: String, private val fg: Int, bg: Drawable?, sp: Float, padV: Int, iconDp: Int = 18
) : LinearLayout(ctx) {
    private val iv = IconView(ctx, icon, fg)
    private val tv = Ui.text(ctx, label, sp, fg, true)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, padV), Ui.dp(ctx, 12), Ui.dp(ctx, padV))
        background = bg
        isClickable = true
        addView(iv, LayoutParams(Ui.dp(ctx, iconDp), Ui.dp(ctx, iconDp)))
        addView(tv, Ui.lp(ctx, Ui.WRAP, Ui.WRAP, start = 8))
    }

    fun setLabel(s: String) {
        tv.text = s
    }

    fun setIcon(kind: Ic) {
        removeView(iv)
        addView(IconView(context, kind, fg), 0, LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18)))
    }
}

/** Thin cyan-to-red divider. */
class Ornament(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        p.strokeWidth = Ui.dpf(context, 1.5f)
        p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.argb(160, 37, 244, 238), Color.argb(160, 254, 44, 85), Shader.TileMode.CLAMP)
        c.drawLine(0f, cy, width.toFloat(), cy, p)
        p.shader = null
    }
}

/**
 * The one switch used everywhere: hollow with a grey outline when off, solid cyan when on.
 * `onTap` lets a permission row open system settings instead of flipping locally.
 */
class Toggle(ctx: Context, var on: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var onTap: (() -> Unit)? = null

    init {
        isClickable = true
        setOnClickListener {
            val t = onTap
            if (t != null) {
                t()
            } else {
                on = !on
                invalidate()
                onChange(on)
            }
        }
    }

    fun setOnSilently(v: Boolean) {
        if (on != v) {
            on = v
            invalidate()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(Ui.dp(context, 62), Ui.dp(context, 32))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        val sw = Ui.dpf(context, 1.6f)
        p.style = Paint.Style.FILL
        if (on) {
            p.color = Ui.CYAN
            c.drawRoundRect(0f, 0f, w, h, r, r, p)
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.color = Ui.GRAY
            c.drawRoundRect(sw / 2, sw / 2, w - sw / 2, h - sw / 2, r, r, p)
        }
        // label on the side opposite to the knob
        p.style = Paint.Style.FILL
        p.typeface = Ui.fBold(context)
        p.textSize = Ui.dpf(context, 10f)
        p.textAlign = Paint.Align.CENTER
        p.color = if (on) Ui.BG else Ui.GRAY
        val lx = if (on) w * 0.30f else w * 0.70f
        c.drawText(if (on) "ON" else "OFF", lx, h / 2 + Ui.dpf(context, 3.6f), p)
        // knob with a soft shadow
        val kr = r - Ui.dpf(context, 4f)
        val kx = if (on) w - r else r
        p.color = Color.argb(46, 0, 0, 0)
        c.drawCircle(kx, r + Ui.dpf(context, 1.2f), kr + Ui.dpf(context, 0.8f), p)
        p.color = Color.WHITE
        c.drawCircle(kx, r, kr, p)
    }
}

/** Card with a header that opens and closes its body. */
class Collapsible(ctx: Context, title: String, subtitle: String = "", open: Boolean = false) : LinearLayout(ctx) {
    val body = LinearLayout(ctx)
    private val chevron = IconView(ctx, Ic.CHEVRON, Ui.MUTED)
    private val sub = Ui.text(ctx, subtitle, 12f, Ui.MUTED)
    private var isOpen = open

    init {
        orientation = VERTICAL
        background = Ui.shape(ctx, Ui.CARD, 18, Ui.LINE)
        val head = LinearLayout(ctx)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 15), Ui.dp(ctx, 16), Ui.dp(ctx, 15))
        val tcol = LinearLayout(ctx)
        tcol.orientation = VERTICAL
        tcol.addView(Ui.text(ctx, title, 16f, Ui.TEXT, true))
        sub.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        tcol.addView(sub)
        head.addView(tcol, LayoutParams(0, Ui.WRAP, 1f))
        head.addView(chevron, LayoutParams(Ui.dp(ctx, 22), Ui.dp(ctx, 22)))
        head.isClickable = true
        head.setOnClickListener {
            isOpen = !isOpen
            apply(true)
        }
        addView(head)
        body.orientation = VERTICAL
        body.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), Ui.dp(ctx, 16))
        addView(body)
        apply(false)
    }

    fun setSubtitle(s: String) {
        sub.text = s
        sub.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
    }

    private fun apply(anim: Boolean) {
        body.visibility = if (isOpen) View.VISIBLE else View.GONE
        val rot = if (isOpen) 180f else 0f
        if (anim) chevron.animate().rotation(rot).setDuration(180).start() else chevron.rotation = rot
    }
}

class BottomBar(private val ctx: Context, items: List<Pair<String, Ic>>, private val onSelect: (Int) -> Unit) {
    val view = LinearLayout(ctx)
    private val icons = ArrayList<IconView>()
    private val labels = ArrayList<TextView>()
    private val marks = ArrayList<View>()

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Color.argb(250, 0, 0, 0))
        val line = View(ctx)
        line.setBackgroundColor(Ui.LINE)
        view.addView(line, LinearLayout.LayoutParams(Ui.MATCH, 1))
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        for (i in items.indices) {
            val tab = LinearLayout(ctx)
            tab.orientation = LinearLayout.VERTICAL
            tab.gravity = Gravity.CENTER_HORIZONTAL
            tab.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 8))
            tab.isClickable = true
            tab.setOnClickListener { select(i, true) }
            val mark = View(ctx)
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.CYAN, Ui.RED))
            g.cornerRadius = Ui.dpf(ctx, 2f)
            mark.background = g
            tab.addView(mark, LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 3)))
            val ic = IconView(ctx, items[i].second, Ui.MUTED)
            tab.addView(ic, Ui.lp(ctx, Ui.dp(ctx, 26), Ui.dp(ctx, 26), top = 6))
            val tx = Ui.text(ctx, items[i].first, 11f, Ui.MUTED, true)
            tab.addView(tx, Ui.lp(ctx, Ui.WRAP, Ui.WRAP, top = 2))
            icons.add(ic); labels.add(tx); marks.add(mark)
            row.addView(tab, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        view.addView(row)
    }

    fun select(i: Int, notify: Boolean) {
        for (k in icons.indices) {
            val sel = k == i
            icons[k].tint(if (sel) Ui.TEXT else Ui.MUTED)
            labels[k].setTextColor(if (sel) Ui.TEXT else Ui.MUTED)
            marks[k].visibility = if (sel) View.VISIBLE else View.INVISIBLE
        }
        if (notify) onSelect(i)
    }
}

/** Wraps children onto new lines. */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    var hGap = Ui.dp(ctx, 8)
    var vGap = Ui.dp(ctx, 8)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == View.GONE) continue
            measureChild(v, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + v.measuredWidth > maxW) {
                x = 0
                y += rowH + vGap
                rowH = 0
            }
            x += v.measuredWidth + hGap
            rowH = maxOf(rowH, v.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = paddingTop
        var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == View.GONE) continue
            if (x > 0 && x + v.measuredWidth > maxW) {
                x = 0
                y += rowH + vGap
                rowH = 0
            }
            val left = if (rtl) (r - l - paddingRight - x - v.measuredWidth) else (paddingLeft + x)
            v.layout(left, y, left + v.measuredWidth, y + v.measuredHeight)
            x += v.measuredWidth + hGap
            rowH = maxOf(rowH, v.measuredHeight)
        }
    }
}
