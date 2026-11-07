package app.dayf

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.dayf.Ui.put

class HomeScreen(act: MainActivity) : Screen(act) {
    private val col = page()
    override val root: View = scroll(col)

    private val power = PowerButton(act)
    private val wave = WaveView(act)
    private val status = Ui.text(act, "", 24f, Ui.TEXT, false, true)
    private val hint = Ui.text(act, "", 13f, Ui.MUTED)
    private val pill = Ui.chip(act, "متوقف", Ui.GRAY)
    private val idName = Ui.text(act, "", 26f, Ui.TEXT, false, true)
    private val idMeta = Ui.text(act, "", 13f, Ui.MUTED)
    private val idVoice = Ui.text(act, "", 13f, Ui.MUTED)
    private val readyBox = LinearLayout(act)
    private val lastLog = Ui.text(act, "", 11f, Ui.MUTED)

    init {
        build()
    }

    private fun build() {
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(KhatamView(act, Ui.GOLD, true), LinearLayout.LayoutParams(dp(50), dp(50)))
        val ttl = LinearLayout(act)
        ttl.orientation = LinearLayout.VERTICAL
        val mark = Ui.text(act, "الضيف", 38f, Ui.GOLD_L)
        mark.typeface = Ui.fCalli(act)
        ttl.addView(mark)
        ttl.addView(Ui.text(act, "حضورٌ بصوتٍ يشبهك", 12f, Ui.MUTED))
        head.addView(ttl, Ui.lp(act, 0, Ui.WRAP, 1f, start = 12))
        head.addView(pill)
        col.put(head)
        col.put(Ornament(act), top = 10, h = dp(16))

        // hero
        val hero = LinearLayout(act)
        hero.orientation = LinearLayout.VERTICAL
        hero.gravity = Gravity.CENTER_HORIZONTAL
        power.setOnClickListener {
            if (AgentService.running) act.stopAgent() else act.begin(false)
        }
        hero.addView(power, LinearLayout.LayoutParams(dp(210), dp(210)))
        status.gravity = Gravity.CENTER
        hint.gravity = Gravity.CENTER
        hero.addView(status, Ui.lp(act, Ui.WRAP, Ui.WRAP, top = 8))
        hero.addView(hint, Ui.lp(act, Ui.WRAP, Ui.WRAP, top = 4))
        hero.addView(wave, Ui.lp(act, Ui.MATCH, dp(54), top = 14))
        col.put(hero, top = 8)

        // identity
        val id = Ui.panel(act)
        val idHead = LinearLayout(act)
        idHead.orientation = LinearLayout.HORIZONTAL
        idHead.gravity = Gravity.CENTER_VERTICAL
        idHead.addView(Ui.sectionHead(act, "هوية الضيف"), Ui.lp(act, 0, Ui.WRAP, 1f))
        idHead.addView(Ui.ghostBtn(act, "تعديل", Ui.GOLD_L, 12f, 6) { act.go(1) })
        id.addView(idHead)
        id.put(idName, top = 10)
        id.put(idMeta, top = 4)
        id.put(idVoice, top = 2)
        col.put(id, top = 18)

        // readiness
        val ready = Ui.panel(act)
        ready.addView(Ui.sectionHead(act, "الجاهزية"))
        readyBox.orientation = LinearLayout.VERTICAL
        ready.put(readyBox, top = 6)
        col.put(ready, top = 14)

        // quick actions
        val qa = LinearLayout(act)
        qa.orientation = LinearLayout.HORIZONTAL
        qa.addView(Ui.ghostBtn(act, "🎧  اختبار الالتقاط", Ui.GOLD_L, 13f, 12) { act.begin(true) }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 6))
        qa.addView(Ui.ghostBtn(act, "🎙  استوديو المحادثة", Ui.GOLD_L, 13f, 12) { act.openStudio() }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 6))
        col.put(qa, top = 14)

        lastLog.layoutDirection = View.LAYOUT_DIRECTION_LTR
        col.put(lastLog, top = 16)
    }

    override fun onShow() = refresh()
    override fun tick() = refresh()

    private fun refresh() {
        val run = AgentService.running
        val missing = Readiness.missing(act)
        pill.text = if (run) "يعمل" else "متوقف"
        pill.setTextColor(if (run) Ui.GREEN else Ui.GRAY)
        pill.background = Ui.shape(act, if (run) 0x3335C79A else 0x336F6B7A, 20)
        power.running = run
        wave.active = run
        status.text = when {
            run -> "الضيف حاضر"
            missing.isNotEmpty() -> "أكمل الإعداد أولاً"
            else -> "جاهز للانطلاق"
        }
        hint.text = when {
            run -> "الفقاعة العائمة تتحكم به فوق تيك توك"
            missing.isNotEmpty() -> "ينقصك: " + missing.joinToString("، ")
            else -> "اضغط الزر لتشغيل الوكيل"
        }

        val c = Repo.cfg()
        idName.text = c.name
        idMeta.text = "لهجة ${c.dialect}"
        idVoice.text = if (c.voiceProvider == "elevenlabs") "الصوت: ElevenLabs • ${if (c.elevenActive == 2) c.elevenName2 else c.elevenName1}"
        else "الصوت: Gemini • ${c.voice}"

        readyBox.removeAllViews()
        for (it in Readiness.items(act)) {
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(Ui.text(act, it.label, 13f, if (it.ok) Ui.TEXT else Ui.MUTED), Ui.lp(act, 0, Ui.WRAP, 1f))
            r.addView(statusDot(act, it.ok, it.optional))
            r.setOnClickListener { act.go(4) }
            readyBox.put(r, top = 9)
        }
        val log = AppLog.text(act).trim().lines().lastOrNull() ?: ""
        lastLog.text = if (log.isBlank()) "" else "› $log"
    }
}
