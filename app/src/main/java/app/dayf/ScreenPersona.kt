package app.dayf

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import app.dayf.Ui.put

class PersonaScreen(act: MainActivity) : Screen(act) {
    private val col = page()
    override val root: View = scroll(col)

    private lateinit var name: EditText
    private lateinit var aliases: EditText
    private lateinit var persona: EditText
    private lateinit var extra: EditText
    private lateinit var dialectCustom: EditText
    private lateinit var greet: Toggle
    private val chips = ArrayList<TextView>()
    private var dialect = ""
    private var filled = false

    init {
        build()
    }

    private fun build() {
        col.put(Ui.title(act, "الشخصية", "من يكون الضيف، وكيف يتكلم"))
        col.put(Ornament(act), top = 10, h = dp(16))

        val p1 = Ui.panel(act)
        p1.addView(Ui.sectionHead(act, "الاسم"))
        p1.put(Ui.label(act, "اسم الضيف كما يناديه الناس"), top = 10)
        name = Ui.field(act, "مثال: جورج", "")
        p1.put(name, top = 4)
        p1.put(Ui.label(act, "أسماء وتهجئات بديلة (يلتقطها التفريغ الصوتي حين يُنادى)، تفصل بينها فاصلة"), top = 12)
        aliases = Ui.field(act, "جورج، جورجي، George", "")
        p1.put(aliases, top = 4)
        col.put(p1, top = 4)

        val p2 = Ui.panel(act)
        p2.addView(Ui.sectionHead(act, "اللهجة"))
        val flow = FlowLayout(act)
        flow.layoutDirection = View.LAYOUT_DIRECTION_RTL
        for (d in Catalog.dialects) {
            val t = Ui.text(act, d, 13f, Ui.MUTED, true)
            t.setPadding(dp(14), dp(8), dp(14), dp(8))
            t.isClickable = true
            t.setOnClickListener {
                dialect = d
                dialectCustom.setText("")
                paintChips()
            }
            chips.add(t)
            flow.addView(t)
        }
        p2.put(flow, top = 12)
        p2.put(Ui.label(act, "أو اكتب لهجة أخرى"), top = 12)
        dialectCustom = Ui.field(act, "مثال: لهجة صفاقسية", "")
        p2.put(dialectCustom, top = 4)
        col.put(p2, top = 14)

        val p3 = Ui.panel(act)
        p3.addView(Ui.sectionHead(act, "وصف الشخصية"))
        p3.put(Ui.label(act, "طبعه، طريقة كلامه، اهتماماته، ما يحب وما لا يحب"), top = 10)
        persona = Ui.field(act, "ضيف ودود وخفيف الظل، يتكلم قليلاً ويصغي كثيراً…", "", true)
        p3.put(persona, top = 4)
        p3.put(Ui.label(act, "تعليمات إضافية (اختياري)"), top = 12)
        extra = Ui.field(act, "أي قيود أو عادات إضافية", "", true)
        extra.minLines = 3
        p3.put(extra, top = 4)
        col.put(p3, top = 14)

        val p4 = Ui.panel(act)
        val gr = LinearLayout(act)
        gr.orientation = LinearLayout.HORIZONTAL
        gr.gravity = Gravity.CENTER_VERTICAL
        val gt = LinearLayout(act)
        gt.orientation = LinearLayout.VERTICAL
        gt.addView(Ui.text(act, "تحية عند الانضمام", 15f, Ui.TEXT, true))
        gt.addView(Ui.text(act, "يحيّي الحاضرين بجملة طبيعية حين يُقبل ضيفاً", 12f, Ui.MUTED))
        gr.addView(gt, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        greet = Toggle(act, true) {}
        gr.addView(greet)
        p4.addView(gr)
        col.put(p4, top = 14)

        col.put(Ui.goldBtn(act, "حفظ الشخصية") { save() }, top = 18)
        col.put(Ui.ghostBtn(act, "🎙  جرّب في الاستوديو") { save(); act.openStudio() }, top = 10)
        col.put(Ui.text(act, "الحفظ يكتب في قاعدة البيانات، ويسري على الجلسة القادمة.", 12f, Ui.MUTED), top = 10)
    }

    private fun paintChips() {
        for (t in chips) {
            val sel = t.text.toString() == dialect && dialectCustom.text.isBlank()
            t.background = if (sel) Ui.goldFill(act, 18) else Ui.shape(act, Ui.CARD2, 18, Ui.LINE)
            t.setTextColor(if (sel) Ui.BG else Ui.MUTED)
        }
    }

    override fun onShow() {
        if (!filled && (Repo.loaded || Repo.raw.length() > 0)) {
            val c = Repo.cfg()
            name.setText(c.name)
            aliases.setText(c.aliases)
            persona.setText(c.persona)
            extra.setText(c.extra)
            greet.on = c.greetOnJoin
            greet.invalidate()
            if (Catalog.dialects.contains(c.dialect)) {
                dialect = c.dialect
                dialectCustom.setText("")
            } else {
                dialect = ""
                dialectCustom.setText(c.dialect)
            }
            filled = true
        }
        paintChips()
    }

    private fun save() {
        val d = dialectCustom.text.toString().trim().ifBlank { dialect.ifBlank { "عربية بيضاء" } }
        val patch = JSONObject()
            .put("name", name.text.toString().trim().ifBlank { "ضيف" })
            .put("aliases", aliases.text.toString().trim())
            .put("dialect", d)
            .put("persona", persona.text.toString().trim())
            .put("extra_instructions", extra.text.toString().trim())
            .put("greet_on_join", greet.on)
        Repo.save(act, patch) { ok, msg -> act.toast(if (ok) "تم حفظ الشخصية" else msg) }
    }
}
