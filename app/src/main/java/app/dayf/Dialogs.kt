package app.dayf

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

object Dialogs {
    class FormField(val label: String, val value: String, val multiline: Boolean = false)

    private fun shell(a: Activity, title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val outer = FrameLayout(a)
        outer.setPadding(Ui.dp(a, 18), Ui.dp(a, 18), Ui.dp(a, 18), Ui.dp(a, 18))
        val p = Ui.panel(a)
        p.layoutDirection = View.LAYOUT_DIRECTION_RTL
        p.addView(Ui.text(a, title, 21f, Ui.TEXT, false, true))
        p.addView(Ornament(a), Ui.lp(a, Ui.MATCH, Ui.dp(a, 14), top = 4, bottom = 6))
        build(p, d)
        val sv = ScrollView(a)
        sv.addView(p)
        outer.addView(sv, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER))
        d.setContentView(outer)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(Ui.MATCH, Ui.WRAP)
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return d
    }

    fun form(a: Activity, title: String, fields: List<FormField>, okLabel: String = "حفظ", onOk: (List<String>) -> Unit) {
        val edits = ArrayList<EditText>()
        val d = shell(a, title) { p, dlg ->
            for (f in fields) {
                p.addView(Ui.label(a, f.label), Ui.lp(a, Ui.WRAP, Ui.WRAP, top = 10))
                val e = Ui.field(a, "", f.value, f.multiline)
                edits.add(e)
                p.addView(e, Ui.lp(a, Ui.MATCH, Ui.WRAP, top = 4))
            }
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(Ui.ghostBtn(a, "إلغاء") { dlg.dismiss() }, Ui.lp(a, 0, Ui.WRAP, 1f, end = 6))
            row.addView(Ui.goldBtn(a, okLabel, 14f, 11) {
                onOk(edits.map { it.text.toString().trim() })
                dlg.dismiss()
            }, Ui.lp(a, 0, Ui.WRAP, 1.4f, start = 6))
            p.addView(row, Ui.lp(a, Ui.MATCH, Ui.WRAP, top = 18))
        }
        d.show()
    }

    fun confirm(a: Activity, title: String, msg: String, okLabel: String = "تأكيد", onOk: () -> Unit) {
        val d = shell(a, title) { p, dlg ->
            p.addView(Ui.text(a, msg, 14f, Ui.MUTED), Ui.lp(a, Ui.MATCH, Ui.WRAP, top = 6))
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(Ui.ghostBtn(a, "إلغاء") { dlg.dismiss() }, Ui.lp(a, 0, Ui.WRAP, 1f, end = 6))
            row.addView(Ui.btn(a, okLabel, Ui.RED, Ui.TEXT, 14f, 11) {
                onOk()
                dlg.dismiss()
            }, Ui.lp(a, 0, Ui.WRAP, 1f, start = 6))
            p.addView(row, Ui.lp(a, Ui.MATCH, Ui.WRAP, top = 18))
        }
        d.show()
    }

    fun message(a: Activity, title: String, msg: String) {
        val d = shell(a, title) { p, dlg ->
            p.addView(Ui.text(a, msg, 14f, Ui.TEXT), Ui.lp(a, Ui.MATCH, Ui.WRAP, top = 6))
            p.addView(Ui.goldBtn(a, "حسناً", 14f, 11) { dlg.dismiss() }, Ui.lp(a, Ui.MATCH, Ui.WRAP, top = 18))
        }
        d.show()
    }
}
