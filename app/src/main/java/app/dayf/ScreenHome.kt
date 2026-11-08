package app.dayf

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import app.dayf.Ui.put

class HomeScreen(act: MainActivity) : Screen(act) {
    private val col = page()
    override val root: View = scroll(col)

    private val power = PowerButton(act)
    private val wave = WaveView(act)
    private val status = Ui.text(act, "", 22f, Ui.TEXT, true)
    private val hint = Ui.text(act, "", 13f, Ui.MUTED)
    private val pill = Ui.chip(act, "متوقف", Ui.GRAY)
    private val idName = Ui.text(act, "", 22f, Ui.TEXT, true)
    private val idMeta = Ui.text(act, "", 13f, Ui.MUTED)
    private val ready = Collapsible(act, "الجاهزية", "", false)
    private val lastLog = Ui.text(act, "", 11f, Ui.MUTED)

    init {
        build()
    }

    private fun build() {
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(act)
        logo.setImageBitmap(Ui.logo(act, "mark"))
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        head.addView(logo, LinearLayout.LayoutParams(dp(34), dp(46)))
        val ttl = LinearLayout(act)
        ttl.orientation = LinearLayout.VERTICAL
        ttl.addView(GlitchText(act, "Tik Guest", 26f, Gravity.START), LinearLayout.LayoutParams(dp(190), Ui.WRAP))
        head.addView(ttl, Ui.lp(act, 0, Ui.WRAP, 1f, start = 8))
        head.addView(pill)
        col.put(head)

        val hero = LinearLayout(act)
        hero.orientation = LinearLayout.VERTICAL
        hero.gravity = Gravity.CENTER_HORIZONTAL
        power.setOnClickListener {
            if (AgentService.running) act.stopAgent() else act.begin(false)
        }
        hero.addView(power, LinearLayout.LayoutParams(dp(200), dp(200)))
        status.gravity = Gravity.CENTER
        hint.gravity = Gravity.CENTER
        hero.addView(status, Ui.lp(act, Ui.WRAP, Ui.WRAP, top = 6))
        hero.addView(hint, Ui.lp(act, Ui.WRAP, Ui.WRAP, top = 4))
        hero.addView(wave, Ui.lp(act, Ui.MATCH, dp(48), top = 14))
        col.put(hero, top = 18)

        val id = Ui.panel(act)
        val idRow = LinearLayout(act)
        idRow.orientation = LinearLayout.HORIZONTAL
        idRow.gravity = Gravity.CENTER_VERTICAL
        val idCol = LinearLayout(act)
        idCol.orientation = LinearLayout.VERTICAL
        idCol.addView(idName)
        idCol.addView(idMeta)
        idRow.addView(idCol, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        idRow.addView(Ui.ghostBtn(act, "تعديل", Ui.CYAN, 12f, 7) { act.go(1) })
        id.addView(idRow)
        col.put(id, top = 20)

        col.put(ready, top = 12)
        col.put(Ui.ghostBtn(act, "🎙  استوديو المحادثة: جرّب بمايك الهاتف", Ui.TEXT, 13f, 13) { act.openStudio() }, top = 12)

        lastLog.layoutDirection = View.LAYOUT_DIRECTION_LTR
        col.put(lastLog, top = 16)
    }

    override fun onShow() = refresh()
    override fun tick() = refresh()

    private fun refresh() {
        val run = AgentService.running
        val items = Readiness.items(act)
        val missing = Readiness.missing(act)
        pill.text = if (run) "يعمل" else "متوقف"
        pill.setTextColor(if (run) Ui.GREEN else Ui.GRAY)
        pill.background = Ui.shape(act, if (run) 0x333DDC97 else 0x336B6C75, 20)
        power.running = run
        wave.active = run
        status.text = when {
            run -> "الضيف حاضر"
            missing.isNotEmpty() -> "أكمل الإعداد أولاً"
            else -> "جاهز للانطلاق"
        }
        hint.text = when {
            run -> "تتحكم به الفقاعة العائمة فوق تيك توك"
            missing.isNotEmpty() -> "ينقصك: " + missing.joinToString("، ")
            else -> "اضغط الزر لتشغيل الوكيل"
        }

        val c = Repo.cfg()
        idName.text = c.name
        idMeta.text = "لهجة ${c.dialect} • " + (if (c.voiceProvider == "elevenlabs") "ElevenLabs" else "Gemini ${c.voice}")

        ready.setSubtitle("${items.count { it.ok }} من ${items.size} جاهز")
        ready.body.removeAllViews()
        for (it in items) {
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(Ui.text(act, it.label, 13f, if (it.ok) Ui.TEXT else Ui.MUTED), Ui.lp(act, 0, Ui.WRAP, 1f))
            r.addView(statusDot(act, it.ok, it.optional))
            r.setOnClickListener { act.go(4) }
            ready.body.put(r, top = 10)
        }
        val log = AppLog.text(act).trim().lines().lastOrNull() ?: ""
        lastLog.text = if (log.isBlank()) "" else "› $log"
    }
}
