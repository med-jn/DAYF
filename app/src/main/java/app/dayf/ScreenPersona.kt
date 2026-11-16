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
    private lateinit var titleF: EditText
    private lateinit var genderSeg: Segmented
    private val currentTitle = Ui.text(act, "", 19f, Ui.TEXT, true)
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
    private val sharePanel = Sharing.Panel(act)

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

        // ---- persona manager: switch, create, duplicate, delete
        val mgr = Ui.panel(act)
        val mrow = LinearLayout(act)
        mrow.orientation = LinearLayout.HORIZONTAL
        mrow.gravity = Gravity.CENTER_VERTICAL
        val mcol = LinearLayout(act)
        mcol.orientation = LinearLayout.VERTICAL
        mcol.addView(Ui.text(act, "الشخصية الحالية", 12f, Ui.MUTED))
        mcol.addView(currentTitle)
        mrow.addView(mcol, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        mrow.addView(Ui.iconGhost(act, Ic.CHEVRON, "تبديل", Ui.CYAN, 13f, 8) { switchDialog() })
        mgr.addView(mrow)
        val brow = LinearLayout(act)
        brow.orientation = LinearLayout.HORIZONTAL
        brow.addView(Ui.iconGhost(act, Ic.PLUS, "جديدة", Ui.TEXT, 12f, 9) { newDialog(false) }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 4))
        brow.addView(Ui.iconGhost(act, Ic.COPY, "نسخة", Ui.TEXT, 12f, 9) { newDialog(true) }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 4, end = 4))
        brow.addView(Ui.iconGhost(act, Ic.TRASH, "حذف", Ui.RED, 12f, 9) { deleteDialog() }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 4))
        mgr.put(brow, top = 12)
        col.put(mgr, top = 16)
        col.put(sharePanel.view, top = 12)

        val p1 = Collapsible(act, "الهوية", "الاسم والتحية", true)
        p1.body.put(Ui.label(act, "اسم الشخصية في قائمتك (للتمييز بينها فقط)"))
        titleF = Ui.field(act, "مثال: ضيف السهرات", "")
        p1.body.put(titleF, top = 4)
        p1.body.put(Ui.label(act, "اسم الضيف كما يناديه الناس"), top = 12)
        name = Ui.field(act, "مثال: جورج", "")
        p1.body.put(name, top = 4)
        p1.body.put(Ui.label(act, "جنس الضيف (يحدد صيغة كلامه عن نفسه بالعربية)"), top = 12)
        genderSeg = Segmented(act, listOf("ذكر", "أنثى"), 0) {}
        p1.body.put(genderSeg.view, top = 4)
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
        col.put(Ui.iconGhost(act, Ic.MIC, "جرّب في الاستوديو", Ui.TEXT, 13f, 12) { save(); act.openStudio() }, top = 10)
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

    override fun reset() {
        filled = false
    }

    private fun refreshTitle() {
        currentTitle.text = Repo.cfg().title
    }

    private fun personaTitles(): List<Pair<String, String>> {
        val a = Repo.personas
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val mine = o.strOr("user_id").let { u -> u.isBlank() || u == Auth.userId(act) }
            o.strOr("id") to (o.strOr("title", o.strOr("name")) + if (mine) "" else " (مشتركة)")
        }
    }

    private fun switchDialog() {
        val list = personaTitles()
        if (list.isEmpty()) return
        val cur = list.indexOfFirst { it.first == Repo.personaId }
        Dialogs.pick(act, "اختر الشخصية", list.map { it.second }, cur) { i ->
            if (list[i].first != Repo.personaId) {
                Repo.switchTo(act, list[i].first) { ok, msg ->
                    act.toast(if (ok) "تم التبديل" else msg)
                    if (ok) act.onPersonaChanged()
                }
            }
        }
    }

    private fun newDialog(copy: Boolean) {
        val base = if (copy) "نسخة من ${Repo.cfg().title}" else ""
        Dialogs.form(act, if (copy) "نسخ الشخصية" else "شخصية جديدة", listOf(Dialogs.FormField("اسم الشخصية", base)), "إنشاء") { v ->
            val t = v[0].ifBlank { "شخصية جديدة" }
            Repo.create(act, t, copy) { ok, msg ->
                act.toast(if (ok) "تم إنشاء «$t»" else msg)
                if (ok) act.onPersonaChanged()
            }
        }
    }

    private fun deleteDialog() {
        if (!Repo.isOwner(act)) {
            Dialogs.confirm(act, "مغادرة الشخصية", "ستتوقف عن استخدام «${Repo.cfg().title}» ولن تظهر في قائمتك. تبقى عند صاحبها.", "مغادرة") {
                val s = Repo.supa(act) ?: return@confirm
                Thread {
                    s.leavePersona(Repo.personaId)
                    Repo.load(act) { act.onPersonaChanged() }
                }.start()
            }
            return
        }
        if (Repo.personas.length() <= 1) {
            act.toast("لا يمكن حذف شخصيتك الوحيدة")
            return
        }
        Dialogs.confirm(act, "حذف الشخصية", "ستُحذف «${Repo.cfg().title}» مع قواعدها ومعارفها نهائياً.", "حذف") {
            Repo.delete(act, Repo.personaId) { ok, msg ->
                act.toast(if (ok) "تم الحذف" else msg)
                if (ok) act.onPersonaChanged()
            }
        }
    }

    override fun onShow() {
        refreshTitle()
        sharePanel.refresh()
        if (!filled && (Repo.loaded || Repo.raw.length() > 0)) {
            val c = Repo.cfg()
            titleF.setText(c.title)
            name.setText(c.name)
            genderSeg.select(if (c.gender == "female") 1 else 0, false)
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
            .put("title", titleF.text.toString().trim().ifBlank { "ضيفي" })
            .put("name", name.text.toString().trim().ifBlank { "ضيف" })
            .put("gender", if (genderSeg.index == 1) "female" else "male")
            .put("aliases", aliases.text.toString().trim())
            .put("dialect", d)
            .put("speech_style", st)
            .put("persona", persona.text.toString().trim())
            .put("extra_instructions", extra.text.toString().trim())
            .put("greet_on_join", greet.on)
        Repo.save(act, patch) { ok, msg -> act.toast(if (ok) "تم حفظ الشخصية" else msg) }
    }
}
