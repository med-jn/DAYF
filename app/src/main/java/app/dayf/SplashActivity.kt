package app.dayf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Launch sequence: the logo settles from a faint chromatic split, a soft ripple leaves it,
 * the wordmark rises in, then everything fades into the app. About 1.7 seconds, tap to skip.
 */
class SplashActivity : Activity() {
    private var done = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (AgentService.running) {
            go()
            return
        }
        val art = SplashArt(this)
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
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var t = 0f
    private var onEnd: (() -> Unit)? = null

    private fun seg(x: Float, from: Float, to: Float): Float = ((x - from) / (to - from)).coerceIn(0f, 1f)
    private fun out(x: Float): Float {
        val k = 1f - x
        return 1f - k * k * k
    }

    fun start(end: () -> Unit) {
        onEnd = end
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = 1700
        an.interpolator = LinearInterpolator()
        an.addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        an.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onEnd?.invoke()
            }
        })
        an.start()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val w = white ?: return
        val cy = cyan ?: return
        val rd = red ?: return
        val d = resources.displayMetrics.density

        val master = 1f - out(seg(t, 0.88f, 1f))
        val appear = out(seg(t, 0f, 0.30f))
        val settle = out(seg(t, 0.04f, 0.50f))
        val scale = 0.90f + 0.10f * out(seg(t, 0f, 0.55f))

        val h = 128f * d
        val bw = h * w.width / w.height
        val cx = width / 2f
        val cyy = height * 0.42f
        val spread = (1f - settle) * 9f * d

        // ripple
        val rp = seg(t, 0.32f, 0.80f)
        if (rp > 0f && rp < 1f) {
            val r = bw * (0.40f + 0.95f * out(rp))
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d
            val al = ((1f - rp) * 110 * master).toInt()
            p.color = Ui.CYAN; p.alpha = al
            c.drawCircle(cx - 2f * d, cyy, r, p)
            p.color = Ui.RED; p.alpha = al
            c.drawCircle(cx + 2f * d, cyy, r, p)
            p.style = Paint.Style.FILL
        }

        // logo
        c.save()
        c.translate(cx, cyy)
        c.scale(scale, scale)
        val rect = RectF(-bw / 2f, -h / 2f, bw / 2f, h / 2f)
        fun layer(bm: Bitmap, dx: Float, al: Float) {
            bp.alpha = (255 * al * master).toInt().coerceIn(0, 255)
            c.save()
            c.translate(dx, 0f)
            c.drawBitmap(bm, null, rect, bp)
            c.restore()
        }
        layer(cy, -spread, appear * 0.95f)
        layer(rd, spread, appear * 0.95f)
        layer(w, 0f, appear)
        c.restore()

        // wordmark
        val wm = out(seg(t, 0.50f, 0.80f))
        if (wm > 0f) {
            val ts = 27f * d
            val ty = cyy + h / 2f + 58f * d - (1f - wm) * 12f * d
            p.typeface = Ui.fBold(a)
            p.textSize = ts
            p.textAlign = Paint.Align.CENTER
            p.letterSpacing = 0.05f
            val off = 1.6f * d
            val al = (255 * wm * master).toInt()
            p.alpha = al; p.color = Ui.CYAN; c.drawText("Tik Guest", cx - off, ty, p)
            p.alpha = al; p.color = Ui.RED; c.drawText("Tik Guest", cx + off, ty, p)
            p.alpha = al; p.color = Color.WHITE; c.drawText("Tik Guest", cx, ty, p)

            // growing gradient line
            val lp = out(seg(t, 0.58f, 0.86f))
            val half = 54f * d * lp
            p.shader = LinearGradient(cx - half, 0f, cx + half, 0f, Ui.CYAN, Ui.RED, Shader.TileMode.CLAMP)
            p.alpha = (200 * master).toInt()
            p.strokeWidth = 2f * d
            c.drawLine(cx - half, ty + 16f * d, cx + half, ty + 16f * d, p)
            p.shader = null

            val sub = out(seg(t, 0.68f, 0.92f))
            p.typeface = Ui.fBody(a)
            p.textSize = 11f * d
            p.letterSpacing = 0.18f
            p.color = Ui.MUTED
            p.alpha = (255 * sub * master).toInt()
            c.drawText("YOUR VOICE · ANY LIVE", cx, ty + 42f * d, p)
            p.letterSpacing = 0f
        }
    }
}
