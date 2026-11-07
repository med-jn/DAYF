package app.dayf

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import app.dayf.Ui.put

class VoiceScreen(act: MainActivity) : Screen(act) {
    private val col = page()
    override val root: View = scroll(col)

    private lateinit var provider: Segmented
    private val geminiBox = LinearLayout(act)
    private val elevenBox = LinearLayout(act)
    private val cards = LinkedHashMap<String, LinearLayout>()
    private var geminiVoice = "Charon"
    private val state = Ui.text(act, "", 13f, Ui.GOLD_L)

    private lateinit var elKey: EditText
    private lateinit var el1Name: EditText
    private lateinit var el1Id: EditText
    private lateinit var el2Name: EditText
    private lateinit var el2Id: EditText
    private lateinit var elActive: Segmented
    private lateinit var elModel: Segmented
    private lateinit var stab: StepperRow
    private lateinit var sim: StepperRow
    private lateinit var gain: StepperRow
    private var filled = false

    init {
        build()
    }

    private fun build() {
        col.put(Ui.title(act, "الصوت", "اختر صوت الضيف بأذنك"))
        col.put(Ornament(act), top = 10, h = dp(16))

        provider = Segmented(act, listOf("أصوات Gemini", "ElevenLabs"), 0) { showProvider(it) }
        col.put(provider.view, top = 2)
        col.put(state, top = 10)

        // ---- Gemini
        geminiBox.orientation = LinearLayout.VERTICAL
        geminiBox.put(Ui.text(act, "٣٠ صوتاً من Gemini. اضغط ▶ للاستماع ثم اختر.", 12f, Ui.MUTED), top = 6)
        val flow = FlowLayout(act)
        flow.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val w = (act.resources.displayMetrics.widthPixels - dp(36) - dp(10)) / 2
        for (v in Catalog.geminiVoices) {
            val card = LinearLayout(act)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(12), dp(11), dp(12), dp(11))
            card.isClickable = true
            val top = LinearLayout(act)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(Ui.text(act, v.name, 16f, Ui.TEXT, false, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            val play = IconView(act, Ic.PLAY, Ui.GOLD_L)
            play.isClickable = true
            play.setOnClickListener { preview(v.name) }
            top.addView(play, LinearLayout.LayoutParams(dp(26), dp(26)))
            card.addView(top)
            card.addView(Ui.text(act, v.ar, 12f, Ui.MUTED))
            card.setOnClickListener {
                geminiVoice = v.name
                paintCards()
            }
            cards[v.name] = card
            flow.addView(card, LinearLayout.LayoutParams(w, Ui.WRAP))
        }
        geminiBox.put(flow, top = 10)
        col.put(geminiBox)

        // ---- ElevenLabs
        elevenBox.orientation = LinearLayout.VERTICAL
        val k = Ui.panel(act)
        k.addView(Ui.sectionHead(act, "الربط"))
        k.put(Ui.label(act, "مفتاح ElevenLabs (يُحفظ على هذا الهاتف فقط)"), top = 10)
        elKey = Ui.field(act, "xi-api-key", "", false, true, true)
        k.put(elKey, top = 4)
        elevenBox.put(k, top = 8)

        val v = Ui.panel(act)
        v.addView(Ui.sectionHead(act, "الأصوات"))
        v.put(Ui.label(act, "الصوت الأول: الاسم ثم المعرّف (Voice ID)"), top = 10)
        el1Name = Ui.field(act, "اسم للتذكير", "")
        v.put(el1Name, top = 4)
        el1Id = Ui.field(act, "Voice ID", "", false, true)
        v.put(el1Id, top = 6)
        v.put(Ui.label(act, "الصوت الثاني"), top = 12)
        el2Name = Ui.field(act, "اسم للتذكير", "")
        v.put(el2Name, top = 4)
        el2Id = Ui.field(act, "Voice ID", "", false, true)
        v.put(el2Id, top = 6)
        v.put(Ui.label(act, "الصوت المستعمل"), top = 12)
        elActive = Segmented(act, listOf("الصوت 1", "الصوت 2"), 0) {}
        v.put(elActive.view, top = 4)
        elevenBox.put(v, top = 12)

        val m = Ui.panel(act)
        m.addView(Ui.sectionHead(act, "الجودة"))
        elModel = Segmented(act, Catalog.elevenModels.map { it.second }, 0) {}
        m.put(elModel.view, top = 10)
        stab = StepperRow(act, "الثبات", 0.45, 0.0, 1.0, 0.05, 2)
        sim = StepperRow(act, "التشابه", 0.8, 0.0, 1.0, 0.05, 2)
        m.put(stab.view, top = 14)
        m.put(sim.view, top = 10)
        m.put(Ui.ghostBtn(act, "▶  استمع إلى الصوت") { previewEleven() }, top = 14)
        elevenBox.put(m, top = 12)
        elevenBox.put(Ui.text(act, "مع ElevenLabs يبقى Gemini هو من يفهم ويفكّر، ثم يُنطق ردّه بصوت ElevenLabs جملةً جملة. قد يزيد التأخير قليلاً.", 12f, Ui.MUTED), top = 10)
        col.put(elevenBox)

        // ---- common
        val c = Ui.panel(act)
        gain = StepperRow(act, "مستوى خرج الصوت", 1.0, 0.1, 1.0, 0.1, 1)
        c.addView(gain.view)
        c.put(Ui.text(act, "اخفضه إن تشوّه الصوت داخل البث بسبب وصلة السماعة.", 12f, Ui.MUTED), top = 8)
        col.put(c, top = 14)

        col.put(Ui.goldBtn(act, "حفظ إعدادات الصوت") { save() }, top = 18)
        showProvider(0)
    }

    private fun showProvider(i: Int) {
        geminiBox.visibility = if (i == 0) View.VISIBLE else View.GONE
        elevenBox.visibility = if (i == 1) View.VISIBLE else View.GONE
    }

    private fun paintCards() {
        for ((name, card) in cards) {
            val sel = name == geminiVoice
            card.background = if (sel) Ui.shape(act, Ui.CARD2, 14, Ui.GOLD, 2) else Ui.shape(act, Ui.CARD, 14, Ui.LINE)
        }
    }

    override fun onShow() {
        if (!filled && (Repo.loaded || Repo.raw.length() > 0)) {
            val c = Repo.cfg()
            geminiVoice = c.voice
            provider.select(if (c.voiceProvider == "elevenlabs") 1 else 0, false)
            showProvider(provider.index)
            el1Name.setText(c.elevenName1)
            el1Id.setText(c.elevenVoice1)
            el2Name.setText(c.elevenName2)
            el2Id.setText(c.elevenVoice2)
            elActive.select(if (c.elevenActive == 2) 1 else 0, false)
            val mi = Catalog.elevenModels.indexOfFirst { it.first == c.elevenModel }
            elModel.select(if (mi >= 0) mi else 0, false)
            filled = true
        }
        if (elKey.text.isBlank()) elKey.setText(Prefs.get(act, "elevenKey"))
        paintCards()
    }

    private fun preview(voice: String) {
        val key = Prefs.get(act, "geminiKey")
        if (key.isBlank()) {
            act.toast("أدخل مفتاح Gemini في الإعدادات")
            return
        }
        state.text = "جارٍ تجهيز الصوت…"
        VoicePreview.gemini(act, key, voice) { s -> act.runOnUiThread { state.text = s } }
    }

    private fun previewEleven() {
        val key = elKey.text.toString().trim()
        val id = (if (elActive.index == 1) el2Id else el1Id).text.toString().trim()
        if (key.isBlank() || id.isBlank()) {
            act.toast("أدخل المفتاح ومعرّف الصوت أولاً")
            return
        }
        VoicePreview.eleven(
            act, key, id, Catalog.elevenModels[elModel.index].first, stab.value, sim.value
        ) { s -> act.runOnUiThread { state.text = s } }
    }

    private fun save() {
        Prefs.put(act, "elevenKey", elKey.text.toString().trim())
        val patch = JSONObject()
            .put("voice_provider", if (provider.index == 1) "elevenlabs" else "gemini")
            .put("voice", geminiVoice)
            .put("eleven_voice_1_name", el1Name.text.toString().trim())
            .put("eleven_voice_id", el1Id.text.toString().trim())
            .put("eleven_voice_2_name", el2Name.text.toString().trim())
            .put("eleven_voice_2_id", el2Id.text.toString().trim())
            .put("eleven_active", if (elActive.index == 1) 2 else 1)
            .put("eleven_model", Catalog.elevenModels[elModel.index].first)
            .put("eleven_stability", stab.value)
            .put("eleven_similarity", sim.value)
            .put("out_gain", gain.value)
        Repo.save(act, patch) { ok, msg -> act.toast(if (ok) "تم حفظ الصوت" else msg) }
    }
}
