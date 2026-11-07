package app.dayf

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import app.dayf.Ui.put

class BrainScreen(act: MainActivity) : Screen(act) {
    private val col = page()
    override val root: View = scroll(col)
    private val listBox = LinearLayout(act)
    private val notice = Ui.text(act, "", 12f, Ui.AMBER)
    private lateinit var seg: Segmented
    private var mode = 0
    private var rows: JSONArray = JSONArray()
    private var loading = false

    init {
        build()
    }

    private fun build() {
        col.put(Ui.title(act, "المعرفة", "ما يعرفه الضيف وما يلتزم به، لا غير"))
        col.put(Ornament(act), top = 10, h = dp(16))
        seg = Segmented(act, listOf("القواعد", "المعرفة"), 0) {
            mode = it
            reload()
        }
        col.put(seg.view, top = 2)
        col.put(notice, top = 10)
        col.put(Ui.goldBtn(act, "＋  إضافة") { edit(null) }, top = 12)
        listBox.orientation = LinearLayout.VERTICAL
        col.put(listBox, top = 6)
    }

    override fun onShow() {
        notice.text = if (Prefs.get(act, "adminKey").isBlank())
            "للتعديل من هنا أدخل «رمز الإدارة» في الإعدادات وشغّل migration_v3.sql في Supabase. القراءة تعمل بدونه."
        else ""
        reload()
    }

    private fun table() = if (mode == 0) "rules" else "knowledge"

    private fun reload() {
        val s = Repo.supa(act)
        if (s == null) {
            listBox.removeAllViews()
            listBox.put(Ui.text(act, "أدخل بيانات Supabase في الإعدادات.", 13f, Ui.MUTED), top = 10)
            return
        }
        if (loading) return
        loading = true
        Thread {
            val r = if (mode == 0) s.listRules() else s.listKnowledge()
            act.runOnUiThread {
                loading = false
                rows = r ?: JSONArray()
                render(r != null, s.lastError)
            }
        }.start()
    }

    private fun render(ok: Boolean, err: String) {
        listBox.removeAllViews()
        if (!ok) {
            listBox.put(Ui.text(act, "تعذّر التحميل: $err", 13f, Ui.RED), top = 10)
            return
        }
        if (rows.length() == 0) {
            listBox.put(Ui.text(act, if (mode == 0) "لا توجد قواعد بعد." else "لا توجد معرفة بعد.", 13f, Ui.MUTED), top = 14)
            return
        }
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val id = o.optLong("id")
            val active = o.optBoolean("active", true)
            val card = Ui.panel(act)
            card.setPadding(dp(16), dp(14), dp(16), dp(12))
            if (mode == 1) card.addView(Ui.text(act, o.strOr("topic"), 16f, Ui.GOLD_L, false, true))
            val body = Ui.text(act, if (mode == 0) o.strOr("text") else o.strOr("content"), 14f, if (active) Ui.TEXT else Ui.GRAY)
            card.put(body, top = if (mode == 1) 4 else 0)
            if (mode == 1 && o.strOr("keywords").isNotBlank()) {
                card.put(Ui.text(act, "كلمات: " + o.strOr("keywords"), 11f, Ui.MUTED), top = 6)
            }
            val act2 = LinearLayout(act)
            act2.orientation = LinearLayout.HORIZONTAL
            act2.gravity = Gravity.CENTER_VERTICAL
            act2.addView(Toggle(act, active) { v -> toggleActive(id, v) })
            act2.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            val ed = IconView(act, Ic.EDIT, Ui.GOLD_L)
            ed.isClickable = true
            ed.setOnClickListener { edit(o) }
            act2.addView(ed, Ui.lp(act, dp(28), dp(28), end = 14))
            val del = IconView(act, Ic.TRASH, Ui.RED)
            del.isClickable = true
            del.setOnClickListener {
                Dialogs.confirm(act, "حذف", "حذف هذا العنصر نهائياً؟", "حذف") { remove(id) }
            }
            act2.addView(del, LinearLayout.LayoutParams(dp(28), dp(28)))
            card.put(act2, top = 12)
            listBox.put(card, top = 10)
        }
    }

    private fun edit(o: JSONObject?) {
        val fields = if (mode == 0) listOf(
            Dialogs.FormField("نص القاعدة", o?.strOr("text") ?: "", true)
        ) else listOf(
            Dialogs.FormField("الموضوع", o?.strOr("topic") ?: ""),
            Dialogs.FormField("المحتوى", o?.strOr("content") ?: "", true),
            Dialogs.FormField("كلمات مفتاحية للبحث (اختياري)", o?.strOr("keywords") ?: "")
        )
        Dialogs.form(act, if (o == null) "إضافة" else "تعديل", fields) { v ->
            val row = JSONObject()
            if (mode == 0) {
                row.put("text", v[0])
                if (o == null) row.put("priority", 100)
            } else {
                row.put("topic", v[0]).put("content", v[1]).put("keywords", v[2])
            }
            if (v[0].isBlank()) {
                act.toast("الحقل فارغ")
                return@form
            }
            write { s -> s.saveRow(table(), o?.optLong("id"), row) }
        }
    }

    private fun toggleActive(id: Long, v: Boolean) {
        write { s -> s.saveRow(table(), id, JSONObject().put("active", v)) }
    }

    private fun remove(id: Long) {
        write { s -> s.removeRow(table(), id) }
    }

    private fun write(op: (Supa) -> Pair<Boolean, String>) {
        val s = Repo.supa(act) ?: return
        Thread {
            val res = op(s)
            act.runOnUiThread {
                act.toast(if (res.first) "تم" else res.second)
                reload()
            }
        }.start()
    }
}
