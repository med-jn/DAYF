package app.dayf

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import app.dayf.Ui.put

/** First screen for signed-out users. */
fun buildLoginView(act: MainActivity): View {
    val col = LinearLayout(act)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER_HORIZONTAL
    col.layoutDirection = View.LAYOUT_DIRECTION_RTL
    val p = Ui.dp(act, 28)
    col.setPadding(p, Ui.dp(act, 70), p, Ui.dp(act, 30))

    val logo = ImageView(act)
    logo.setImageBitmap(Ui.logo(act, "mark"))
    logo.scaleType = ImageView.ScaleType.FIT_CENTER
    col.addView(logo, LinearLayout.LayoutParams(Ui.dp(act, 96), Ui.dp(act, 130)))
    col.addView(GlitchText(act, "Tik Guest", 34f, Gravity.CENTER_HORIZONTAL), Ui.lp(act, Ui.MATCH, Ui.WRAP, top = 14))
    val sub = Ui.text(act, "ضيفك الصوتي في أي بث مباشر", 14f, Ui.MUTED)
    sub.gravity = Gravity.CENTER
    col.put(sub, top = 4)

    col.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))

    col.put(Ui.btn(act, "المتابعة بحساب Google", Ui.TEXT, Ui.BG, 16f, 15) { Auth.beginLogin(act) })
    val note = Ui.text(
        act,
        "شخصياتك وقواعدك ومعارفك تُحفظ في حسابك ولا يراها غيرك. لا نحفظ ما يقوله الآخرون في البث أبداً.",
        12f, Ui.MUTED
    )
    note.gravity = Gravity.CENTER
    col.put(note, top = 16)
    if (Config.PRIVACY_URL.isNotBlank()) {
        col.put(Ui.ghostBtn(act, "سياسة الخصوصية", Ui.CYAN, 12f, 8) {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.PRIVACY_URL)))
        }, top = 10)
    }
    return col
}
