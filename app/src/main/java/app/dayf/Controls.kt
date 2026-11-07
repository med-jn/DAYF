package app.dayf

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

abstract class Screen(val act: MainActivity) {
    abstract val root: View
    open fun onShow() {}
    open fun tick() {}

    protected fun dp(v: Int) = Ui.dp(act, v)

    protected fun page(): LinearLayout {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.layoutDirection = View.LAYOUT_DIRECTION_RTL
        c.setPadding(dp(18), dp(44), dp(18), dp(28))
        return c
    }

    protected fun scroll(content: View): ScrollView {
        val sv = ScrollView(act)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(content)
        return sv
    }
}

class StepperRow(
    ctx: Context, label: String, value: Double,
    private val min: Double, private val max: Double, private val step: Double,
    private val decimals: Int, private val suffix: String = ""
) {
    var value: Double = value
        private set
    val view = LinearLayout(ctx)
    private val valueText = Ui.text(ctx, "", 15f, Ui.GOLD_L, true)

    init {
        view.orientation = LinearLayout.HORIZONTAL
        view.gravity = Gravity.CENTER_VERTICAL
        view.addView(Ui.text(ctx, label, 14f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val minus = Ui.ghostBtn(ctx, "−", Ui.GOLD_L, 18f, 0) { bump(-step) }
        val plus = Ui.ghostBtn(ctx, "+", Ui.GOLD_L, 18f, 0) { bump(step) }
        view.addView(minus, LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 38)))
        valueText.gravity = Gravity.CENTER
        view.addView(valueText, LinearLayout.LayoutParams(Ui.dp(ctx, 72), Ui.WRAP))
        view.addView(plus, LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 38)))
        render()
    }

    private fun bump(d: Double) {
        val f = Math.pow(10.0, decimals.toDouble())
        value = Math.round((value + d).coerceIn(min, max) * f) / f
        render()
    }

    private fun render() {
        valueText.text = (if (decimals == 0) value.toInt().toString() else String.format(java.util.Locale.US, "%.${decimals}f", value)) + suffix
    }
}

object Readiness {
    class Item(val label: String, val optional: Boolean, val ok: Boolean)

    fun a11y(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(c.packageName)
    }

    fun items(c: Context): List<Item> {
        val keys = Prefs.get(c, "geminiKey").isNotBlank() && Prefs.get(c, "supaUrl").isNotBlank() && Prefs.get(c, "supaKey").isNotBlank()
        val mic = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val battery = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
        return listOf(
            Item("الظهور فوق التطبيقات", false, Settings.canDrawOverlays(c)),
            Item("صلاحية الميكروفون", false, mic),
            Item("المفاتيح", false, keys),
            Item("قاعدة البيانات", false, Repo.loaded),
            Item("خدمة الإتاحة (اختيارية)", true, a11y(c)),
            Item("استثناء البطارية (يُنصح به)", true, battery)
        )
    }

    fun missing(c: Context): List<String> = items(c).filter { !it.optional && !it.ok }.map { it.label }
}

fun statusDot(ctx: Context, ok: Boolean, optional: Boolean): TextView {
    val t = Ui.text(ctx, if (ok) "✔" else if (optional) "○" else "✘", 16f, if (ok) Ui.GREEN else if (optional) Ui.AMBER else Ui.RED, true)
    t.gravity = Gravity.CENTER
    return t
}
