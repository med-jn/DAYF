package app.dayf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Eight-pointed star (two overlaid squares) — the mark of the app. */
fun drawKhatam(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
    for (rot in intArrayOf(45, 0)) {
        val path = Path()
        for (k in 0 until 4) {
            val a = Math.toRadians((rot + 90 * k).toDouble())
            val x = cx + r * cos(a).toFloat()
            val y = cy + r * sin(a).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }
}

class KhatamView(ctx: Context, private val color: Int, private val withWave: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val r = min(w, h) / 2 * 0.86f
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = maxOf(1.4f, r * 0.09f)
        drawKhatam(c, cx, cy, r, p)
        if (withWave) {
            p.strokeWidth = maxOf(1f, r * 0.05f)
            p.color = Ui.GOLD_D
            drawKhatam(c, cx, cy, r * 0.56f, p)
            p.style = Paint.Style.FILL
            p.color = Ui.CARD
            c.drawCircle(cx, cy, r * 0.40f, p)
            p.color = Ui.GOLD_L
            val hs = floatArrayOf(0.30f, 0.62f, 1f, 0.62f, 0.30f)
            val bw = r * 0.08f
            val gap = r * 0.052f
            var x = cx - (hs.size * bw + (hs.size - 1) * gap) / 2
            for (v in hs) {
                val bh = r * 0.42f * v
                c.drawRoundRect(x, cy - bh / 2, x + bw, cy + bh / 2, bw / 2, bw / 2, p)
                x += bw + gap
            }
        }
    }
}

/** Live level meter. Animates by itself while attached. */
class WaveView(ctx: Context) : View(ctx) {
    var active = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = 33
    private val hs = FloatArray(n)
    private var t = 0f

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
            p.color = if (speaking && active) Ui.BLUE else if (active) Ui.GOLD_L else Ui.GOLD_D
            p.alpha = (80 + 175 * env).toInt()
            c.drawRoundRect(x - bw / 2, (hh - bh) / 2, x + bw / 2, (hh + bh) / 2, bw / 2, bw / 2, p)
        }
        postInvalidateOnAnimation()
    }
}

/** The big dial-like start/stop control. */
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
        val r = min(w, h) / 2 - Ui.dpf(context, 4f)
        // dial ticks
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0)
            val major = i % 6 == 0
            val r1 = r * (if (major) 0.90f else 0.94f)
            p.color = if (major) Ui.GOLD else Ui.GOLD_D
            p.alpha = if (major) 255 else 150
            p.strokeWidth = Ui.dpf(context, if (major) 2f else 1f)
            c.drawLine(
                cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), p
            )
        }
        // pulse
        if (running) {
            t += 0.012f
            val ph = t % 1f
            p.color = Ui.GOLD_L
            p.alpha = ((1f - ph) * 120).toInt()
            p.strokeWidth = Ui.dpf(context, 2f)
            c.drawCircle(cx, cy, r * (0.70f + 0.22f * ph), p)
        }
        // outer ring
        p.alpha = 255
        p.color = Ui.GOLD_D
        p.strokeWidth = Ui.dpf(context, 1.5f)
        c.drawCircle(cx, cy, r * 0.84f, p)
        // disc
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy - r * 0.2f, r * 0.8f, Ui.CARD2, Ui.BG, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.74f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.color = Ui.GOLD
        p.strokeWidth = Ui.dpf(context, 2.2f)
        c.drawCircle(cx, cy, r * 0.74f, p)
        // glyph
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r * 0.3f, Ui.GOLD_L, Ui.GOLD, Shader.TileMode.CLAMP)
        if (running) {
            val s = r * 0.22f
            c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.28f, s * 0.28f, p)
        } else {
            val path = Path()
            val s = r * 0.27f
            path.moveTo(cx - s * 0.7f, cy - s)
            path.lineTo(cx + s * 1.05f, cy)
            path.lineTo(cx - s * 0.7f, cy + s)
            path.close()
            c.drawPath(path, p)
        }
        p.shader = null
        if (running) postInvalidateOnAnimation()
    }
}

enum class Ic { HOME, PERSONA, BOOK, WAVE, SLIDERS, PLAY, PLUS, TRASH, EDIT, MIC, BACK, STUDIO }

/** Hand-drawn line icons on a 24-unit grid. */
class IconView(ctx: Context, private val kind: Ic, var color: Int = Ui.TEXT) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun tint(c: Int) {
        color = c
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height) / 24f
        c.save()
        c.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        c.scale(s, s)
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = 1.8f
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
            Ic.PLAY -> {
                p.style = Paint.Style.FILL
                path.moveTo(7f, 4.5f); path.lineTo(19f, 12f); path.lineTo(7f, 19.5f); path.close()
                c.drawPath(path, p)
            }
            Ic.PLUS -> {
                c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p)
            }
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
            Ic.BACK -> {
                path.moveTo(9f, 5f); path.lineTo(16f, 12f); path.lineTo(9f, 19f)
                c.drawPath(path, p)
            }
            Ic.STUDIO -> {
                c.drawRoundRect(RectF(3f, 6f, 21f, 18f), 3f, 3f, p)
                p.style = Paint.Style.FILL
                path.moveTo(10.5f, 9.5f); path.lineTo(14.5f, 12f); path.lineTo(10.5f, 14.5f); path.close()
                c.drawPath(path, p)
            }
        }
        c.restore()
    }
}

/** Line — diamond — line. */
class Ornament(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cy = height / 2f
        val d = Ui.dpf(context, 4f)
        val gap = Ui.dpf(context, 12f)
        p.strokeWidth = Ui.dpf(context, 1f)
        p.shader = LinearGradient(0f, 0f, w / 2 - gap, 0f, Color.TRANSPARENT, Ui.GOLD_D, Shader.TileMode.CLAMP)
        c.drawLine(0f, cy, w / 2 - gap, cy, p)
        p.shader = LinearGradient(w / 2 + gap, 0f, w, 0f, Ui.GOLD_D, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawLine(w / 2 + gap, cy, w, cy, p)
        p.shader = null
        p.color = Ui.GOLD
        p.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(w / 2, cy - d); path.lineTo(w / 2 + d, cy); path.lineTo(w / 2, cy + d); path.lineTo(w / 2 - d, cy); path.close()
        c.drawPath(path, p)
    }
}

class Toggle(ctx: Context, var on: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener {
            on = !on
            invalidate()
            onChange(on)
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(Ui.dp(context, 50), Ui.dp(context, 28))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        p.style = Paint.Style.FILL
        if (on) {
            p.shader = LinearGradient(0f, 0f, w, 0f, Ui.GOLD_L, Ui.GOLD, Shader.TileMode.CLAMP)
        } else {
            p.shader = null
            p.color = Ui.CARD2
        }
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        p.shader = null
        if (!on) {
            p.style = Paint.Style.STROKE
            p.color = Ui.LINE
            p.strokeWidth = Ui.dpf(context, 1f)
            c.drawRoundRect(1f, 1f, w - 1, h - 1, r, r, p)
        }
        p.style = Paint.Style.FILL
        p.color = if (on) Ui.BG else Ui.MUTED
        val kx = if (on) w - r else r
        c.drawCircle(kx, r, r - Ui.dpf(context, 4f), p)
    }
}

/** Window background: ink with a faint star lattice and a warm glow. */
class ArabesqueBg(private val ctx: Context) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Ui.BG
        c.drawRect(b, p)
        p.shader = RadialGradient(
            b.width() * 0.85f, 0f, b.width() * 0.9f,
            Color.argb(46, 200, 162, 74), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        c.drawRect(b, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.dpf(ctx, 0.8f)
        p.color = Color.argb(18, 235, 208, 138)
        val step = Ui.dpf(ctx, 84f)
        val r = Ui.dpf(ctx, 15f)
        var row = 0
        var y = 0f
        while (y < b.height() + step) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < b.width() + step) {
                drawKhatam(c, x, y, r, p)
                x += step
            }
            y += step / 2
            row++
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

class BottomBar(private val ctx: Context, items: List<Pair<String, Ic>>, private val onSelect: (Int) -> Unit) {
    val view = LinearLayout(ctx)
    private val icons = ArrayList<IconView>()
    private val labels = ArrayList<TextView>()
    private val marks = ArrayList<View>()

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Color.argb(245, 10, 9, 16))
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
            mark.background = Ui.goldFill(ctx, 2)
            tab.addView(mark, LinearLayout.LayoutParams(Ui.dp(ctx, 22), Ui.dp(ctx, 3)))
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
            icons[k].tint(if (sel) Ui.GOLD_L else Ui.MUTED)
            labels[k].setTextColor(if (sel) Ui.GOLD_L else Ui.MUTED)
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
