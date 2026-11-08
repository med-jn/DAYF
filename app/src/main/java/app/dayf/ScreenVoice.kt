package app.dayf

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
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
    private val state = Ui.text(act, "", 13f, Ui.CYAN)

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

        provider = Segmented(act, listOf("أصوات Gemini", "ElevenLabs"), 0) { showProvider(it) }
        col.put(provider.view, top = 16)
        col.put(state, top = 8)

        // ---- Gemini
        geminiBox.orientation = LinearLayout.VERTICAL
        val gv = Collapsible(act, "الأصوات", "٣٠ صوتاً: اضغط ▶ للاستماع ثم اختر", true)
        val flow = FlowLayout(act)
        flow.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val w = (act.resources.displayMetrics.widthPixels - dp(36) - dp(32) - dp(8)) / 2
        for (v in Catalog.geminiVoices) {
            val card = LinearLayout(act)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(12), dp(11), dp(12), dp(11))
            card.isClickable = true
            val top = LinearLayout(act)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(Ui.text(act, v.name, 15f, Ui.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            val play = IconView(act, Ic.PLAY, Ui.CYAN)
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
        gv.body.put(flow, top = 4)
        geminiBox.put(gv)
        col.put(geminiBox, top = 12)

        // ---- ElevenLabs
        elevenBox.orientation = LinearLayout.VERTICAL
        val k = Collapsible(act, "الربط", "مفتاح ElevenLabs (يبقى على هذا الهاتف)", true)
        elKey = Ui.field(act, "xi-api-key", "", false, true, true)
        k.body.put(elKey)
        elevenBox.put(k)

        val v = Collapsible(act, "الأصوات", "صوتان يمكن التبديل بينهما", true)
        v.body.put(Ui.label(act, "الصوت 1: الاسم ثم المعرّف (Voice ID)"))
        el1Name = Ui.field(act, "اسم للتذكير", "")
        v.body.put(el1Name, top = 4)
        el1Id = Ui.field(act, "Voice ID", "", false, true)
        v.body.put(el1Id, top = 6)
        v.body.put(Ui.label(act, "الصوت 2"), top = 12)
        el2Name = Ui.field(act, "اسم للتذكير", "")
        v.body.put(el2Name, top = 4)
        el2Id = Ui.field(act, "Voice ID", "", false, true)
        v.body.put(el2Id, top = 6)
        v.body.put(Ui.label(act, "المستعمل الآن"), top = 12)
        elActive = Segmented(act, listOf("الصوت 1", "الصوت 2"), 0) {}
        v.body.put(elActive.view, top = 4)
        elevenBox.put(v, top = 12)

        val m = Collapsible(act, "الجودة", "النموذج والثبات والتشابه", false)
        elModel = Segmented(act, Catalog.elevenModels.map { it.second }, 0) {}
        m.body.put(elModel.view)
        stab = StepperRow(act, "الثبات", 0.45, 0.0, 1.0, 0.05, 2)
        sim = StepperRow(act, "التشابه", 0.8, 0.0, 1.0, 0.05, 2)
        m.body.put(stab.view, top = 14)
        m.body.put(sim.view, top = 10)
        elevenBox.put(m, top = 12)
        elevenBox.put(Ui.ghostBtn(act, "▶  استمع إلى صوت ElevenLabs", Ui.CYAN, 13f, 12) { previewEleven() }, top = 12)
        elevenBox.put(
            Ui.text(act, "Gemini يفهم ويفكّر، ثم يُنطق ردّه بصوت ElevenLabs جملةً جملة، فقد يزيد التأخير قليلاً.", 12f, Ui.MUTED),
            top = 8
        )
        col.put(elevenBox, top = 12)

        // ---- common
        val c = Collapsible(act, "مستوى الخرج", "اخفضه إن تشوّه الصوت داخل البث", false)
        gain = StepperRow(act, "مستوى الصوت", 1.0, 0.1, 1.0, 0.1, 1)
        c.body.addView(gain.view)
        col.put(c, top = 12)

        col.put(Ui.primaryBtn(act, "حفظ إعدادات الصوت") { save() }, top = 20)
        showProvider(0)
    }

    private fun showProvider(i: Int) {
        geminiBox.visibility = if (i == 0) View.VISIBLE else View.GONE
        elevenBox.visibility = if (i == 1) View.VISIBLE else View.GONE
    }

    private fun paintCards() {
        for ((name, card) in cards) {
            val sel = name == geminiVoice
            card.background = if (sel) Ui.shape(act, Ui.CARD2, 14, Ui.CYAN, 2) else Ui.shape(act, Ui.BG, 14, Ui.LINE)
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
            stab.set(c.elevenStability)
            sim.set(c.elevenSimilarity)
            gain.set(c.outGain)
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
