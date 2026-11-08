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
    private lateinit var styleCustom: EditText
    private lateinit var greet: Toggle
    private lateinit var styleBox: Collapsible
    private val dialectChips = ArrayList<TextView>()
    private val styleChips = ArrayList<TextView>()
    private var dialect = ""
    private var style = ""
    private var filled = false

    init {
        build()
    }

    private fun chips(items: List<String>, list: ArrayList<TextView>, onPick: (String) -> Unit): FlowLayout {
        val flow = FlowLayout(act)
        flow.layoutDirection = View.LAYOUT_DIRECTION_RTL
        for (d in items) {
            val t = Ui.text(act, d, 13f, Ui.MUTED, true)
            t.setPadding(dp(14), dp(8), dp(14), dp(8))
            t.isClickable = true
            t.setOnClickListener { onPick(d) }
            list.add(t)
            flow.addView(t)
        }
        return flow
    }

    private fun build() {
        col.put(Ui.title(act, "الشخصية", "من يكون الضيف وكيف يتكلم"))

        val p1 = Collapsible(act, "الهوية", "الاسم والتحية", true)
        p1.body.put(Ui.label(act, "اسم الضيف كما يناديه الناس"))
        name = Ui.field(act, "مثال: جورج", "")
        p1.body.put(name, top = 4)
        p1.body.put(Ui.label(act, "أسماء وتهجئات بديلة (يلتقطها التفريغ الصوتي حين يُنادى)، تفصل بينها فاصلة"), top = 12)
        aliases = Ui.field(act, "جورج، جورجي، George", "")
        p1.body.put(aliases, top = 4)
        val gr = LinearLayout(act)
        gr.orientation = LinearLayout.HORIZONTAL
        gr.gravity = Gravity.CENTER_VERTICAL
        val gt = LinearLayout(act)
        gt.orientation = LinearLayout.VERTICAL
        gt.addView(Ui.text(act, "تحية عند الانضمام", 14f, Ui.TEXT, true))
        gt.addView(Ui.text(act, "يحيّي الحاضرين بجملة طبيعية حين يُقبل ضيفاً", 12f, Ui.MUTED))
        gr.addView(gt, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        greet = Toggle(act, true) {}
        gr.addView(greet)
        p1.body.put(gr, top = 16)
        col.put(p1, top = 16)

        val p2 = Collapsible(act, "اللهجة", "", true)
        p2.body.put(chips(Catalog.dialects, dialectChips) {
            dialect = it
            dialectCustom.setText("")
            paint()
        })
        p2.body.put(Ui.label(act, "أو اكتب لهجة أخرى"), top = 12)
        dialectCustom = Ui.field(act, "مثال: لهجة صفاقسية", "")
        p2.body.put(dialectCustom, top = 4)
        col.put(p2, top = 12)

        styleBox = Collapsible(act, "الأداء الصوتي", "", false)
        styleBox.body.put(Ui.text(act, "طريقة أداء الصوت: النبرة والإيقاع والحماس", 12f, Ui.MUTED))
        styleBox.body.put(chips(Catalog.styles, styleChips) {
            style = it
            styleCustom.setText("")
            paint()
        }, top = 10)
        styleBox.body.put(Ui.label(act, "أو صف الأداء بكلماتك"), top = 12)
        styleCustom = Ui.field(act, "مثال: هادئ يميل للسخرية الخفيفة", "")
        styleBox.body.put(styleCustom, top = 4)
        col.put(styleBox, top = 12)

        val p3 = Collapsible(act, "وصف الشخصية", "الطبع والاهتمامات والتعليمات", false)
        p3.body.put(Ui.label(act, "طبعه، طريقة كلامه، اهتماماته، ما يحب وما لا يحب"))
        persona = Ui.field(act, "ضيف ودود وخفيف الظل، يتكلم قليلاً ويصغي كثيراً…", "", true)
        p3.body.put(persona, top = 4)
        p3.body.put(Ui.label(act, "تعليمات إضافية (اختياري)"), top = 12)
        extra = Ui.field(act, "أي قيود أو عادات إضافية", "", true)
        extra.minLines = 3
        p3.body.put(extra, top = 4)
        col.put(p3, top = 12)

        col.put(Ui.primaryBtn(act, "حفظ الشخصية") { save() }, top = 20)
        col.put(Ui.ghostBtn(act, "🎙  جرّب في الاستوديو", Ui.TEXT, 13f, 12) { save(); act.openStudio() }, top = 10)
    }

    private fun paintGroup(list: ArrayList<TextView>, current: String, custom: EditText) {
        for (t in list) {
            val sel = t.text.toString() == current && custom.text.isBlank()
            t.background = if (sel) Ui.shape(act, Ui.CYAN, 18) else Ui.shape(act, Ui.CARD2, 18, Ui.LINE)
            t.setTextColor(if (sel) Ui.BG else Ui.MUTED)
        }
    }

    private fun paint() {
        paintGroup(dialectChips, dialect, dialectCustom)
        paintGroup(styleChips, style, styleCustom)
        styleBox.setSubtitle(styleCustom.text.toString().ifBlank { style })
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
            if (Catalog.styles.contains(c.speechStyle)) {
                style = c.speechStyle
                styleCustom.setText("")
            } else {
                style = ""
                styleCustom.setText(c.speechStyle)
            }
            filled = true
        }
        paint()
    }

    private fun save() {
        val d = dialectCustom.text.toString().trim().ifBlank { dialect.ifBlank { "عربية بيضاء" } }
        val st = styleCustom.text.toString().trim().ifBlank { style }
        val patch = JSONObject()
            .put("name", name.text.toString().trim().ifBlank { "ضيف" })
            .put("aliases", aliases.text.toString().trim())
            .put("dialect", d)
            .put("speech_style", st)
            .put("persona", persona.text.toString().trim())
            .put("extra_instructions", extra.text.toString().trim())
            .put("greet_on_join", greet.on)
        Repo.save(act, patch) { ok, msg -> act.toast(if (ok) "تم حفظ الشخصية" else msg) }
    }
}
