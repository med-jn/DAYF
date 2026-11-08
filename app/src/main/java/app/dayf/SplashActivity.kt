package app.dayf

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/** The logo assembles from a glitch: cyan and red layers slide in, digital slices settle, wordmark appears. */
class SplashActivity : Activity() {
    private lateinit var art: SplashArt
    private var done = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (AgentService.running) {
            go()
            return
        }
        art = SplashArt(this)
        art.setOnClickListener { go() }
        setContentView(art)
        art.start { go() }
    }

    private fun go() {
        if (done) return
        done = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

private class SplashArt(private val a: Activity) : View(a) {
    private val white: Bitmap? = Ui.logo(a, "white")
    private val cyan: Bitmap? = Ui.logo(a, "cyan")
    private val red: Bitmap? = Ui.logo(a, "red")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = Random(7)
    private var prog = 0f
    private var onEnd: (() -> Unit)? = null
    private val dst = Rect()
    private val src = Rect()

    private fun ease(x: Float): Float { val t = x.coerceIn(0f, 1f); return 1f - (1f - t) * (1f - t) * (1f - t) }
    private fun seg(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

    fun start(end: () -> Unit) {
        onEnd = end
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = 2300
        an.interpolator = DecelerateInterpolator(0.4f)
        an.addUpdateListener {
            prog = it.animatedValue as Float
            invalidate()
        }
        an.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                postDelayed({ onEnd?.invoke() }, 250)
            }
        })
        an.start()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val w = white ?: return
        val cy = cyan ?: return
        val rd = red ?: return
        val t = prog
        val h = height * 0.34f
        val bw = h * w.width / w.height
        val left = (width - bw) / 2f
        val top = height * 0.30f
        val d = Ui.dpf(a, 1f)

        val appear = ease(seg(t, 0.00f, 0.30f))
        val converge = ease(seg(t, 0.12f, 0.62f))
        val glitchAmp = (1f - seg(t, 0.20f, 0.66f))
        val spread = (1f - converge) * 70f * d

        // horizontal glitch slices: the logo is cut into bands that jitter and settle
        val bands = 9
        val bandH = h / bands
        for (i in 0 until bands) {
            val jitter = if (glitchAmp > 0.02f) (rnd.nextFloat() - 0.5f) * 36f * d * glitchAmp else 0f
            val y0 = top + i * bandH
            c.save()
            c.clipRect(0f, y0, width.toFloat(), y0 + bandH + 1f)
            c.translate(jitter, 0f)
            drawLayers(c, w, cy, rd, left, top, bw, h, spread, appear)
            c.restore()
        }

        // wordmark
        val wm = ease(seg(t, 0.55f, 0.85f))
        if (wm > 0f) {
            val ts = height * 0.045f
            val ty = top + h + height * 0.085f - (1f - wm) * 14f * d
            p.typeface = Ui.fBold(a)
            p.textSize = ts
            p.textAlign = Paint.Align.CENTER
            val cx = width / 2f
            val off = 2.2f * d * (1f - 0.4f * wm)
            p.alpha = (255 * wm).toInt()
            p.color = Ui.CYAN; c.drawText("Tik Guest", cx - off, ty, p)
            p.color = Ui.RED; c.drawText("Tik Guest", cx + off, ty, p)
            p.color = Color.WHITE; c.drawText("Tik Guest", cx, ty, p)
            val sub = ease(seg(t, 0.72f, 1f))
            p.typeface = Ui.fBody(a)
            p.textSize = ts * 0.42f
            p.color = Ui.MUTED
            p.alpha = (255 * sub).toInt()
            c.drawText("YOUR VOICE · ANY LIVE", cx, ty + ts * 0.95f, p)
        }
    }

    private fun drawLayers(c: Canvas, w: Bitmap, cy: Bitmap, rd: Bitmap, left: Float, top: Float, bw: Float, h: Float, spread: Float, appear: Float) {
        dst.set(0, 0, 0, 0)
        fun draw(bm: Bitmap, dx: Float, alpha: Float, pnt: Paint) {
            src.set(0, 0, bm.width, bm.height)
            val r = android.graphics.RectF(left + dx, top, left + dx + bw, top + h)
            pnt.alpha = (255 * alpha).toInt()
            c.drawBitmap(bm, src, r, pnt)
        }
        p.xfermode = null
        draw(cy, -spread, appear, p)
        draw(rd, spread, appear, p)
        wp.alpha = 255
        draw(w, 0f, ease(max(0f, min(1f, (appear - 0.2f) / 0.8f))), wp)
    }
}
