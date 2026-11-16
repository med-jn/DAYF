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
    private val notice = Ui.text(act, "", 12f, Ui.MUTED)
    private lateinit var seg: Segmented
    private var mode = UiState.brainMode
    private var rows: JSONArray = JSONArray()
    private var loading = false
    private var ticks = 0
    private var folders: JSONArray = JSONArray()
    private var openFolder: String?
        get() = UiState.folder
        set(v) { UiState.folder = v }
    private var query = ""
    private var preview: android.media.MediaPlayer? = null
    private var previewId: String? = null

    init {
        build()
    }

    private fun build() {
        col.put(Ui.title(act, "المعرفة", "ما يعرفه الضيف وما يلتزم به، ومراجعه"))
        seg = Segmented(act, listOf("القواعد", "المعرفة", "المراجع"), UiState.brainMode) {
            mode = it
            UiState.brainMode = it
            updateNotice()
            reload()
        }
        col.put(seg.view, top = 16)
        col.put(notice, top = 10)
        col.put(Ui.iconPrimary(act, Ic.PLUS, "إضافة", 14f, 12) { add() }, top = 12)
        listBox.orientation = LinearLayout.VERTICAL
        col.put(listBox, top = 6)
    }

    override fun onShow() {
        updateNotice()
        reload()
    }

    override fun tick() {
        // while a source is being read (or waits for its automatic retry) keep the list fresh and let due retries start
        if (mode != 2) return
        ticks++
        var active = false
        for (i in 0 until rows.length()) {
            val st = rows.getJSONObject(i).optString("status")
            if (st == "pending" || st == "retry") active = true
        }
        if (active && ticks % 4 == 0) {
            Ingest.sweepRows(act, rows)
            reload()
        }
    }

    private fun updateNotice() {
        notice.text = when (mode) {
            0 -> "قواعد صارمة يلتزم بها الضيف، خاصة بالشخصية الحالية: ${Repo.cfg().title}"
            1 -> "معلومات يعتمدها الضيف كحقائق، خاصة بالشخصية الحالية: ${Repo.cfg().title}"
            else -> "مراجع تُقرأ وتُفهرس مرة واحدة، ويستند إليها الضيف في إجاباته"
        }
    }

    private fun table() = if (mode == 0) "rules" else "knowledge"

    private fun reload() {
        val s = Repo.supa(act)
        if (s == null || Repo.personaId.isBlank()) {
            listBox.removeAllViews()
            listBox.put(Ui.text(act, "سجّل الدخول أولاً.", 13f, Ui.MUTED), top = 10)
            return
        }
        if (loading) return
        loading = true
        Thread {
            val pid = Repo.personaId
            val r = when (mode) {
                0 -> s.listRules(pid)
                1 -> s.listKnowledge(pid)
                else -> s.listSources(pid)
            }
            val fl = if (mode == 2) s.listFolders(pid) else null
            act.runOnUiThread {
                loading = false
                rows = r ?: JSONArray()
                if (fl != null) folders = fl
                render(r != null, s.lastError)
                if (mode == 2 && r != null) Ingest.sweepRows(act, rows)
            }
        }.start()
    }

    private fun render(ok: Boolean, err: String) {
        listBox.removeAllViews()
        if (!ok) {
            listBox.put(Ui.text(act, "تعذّر التحميل: $err", 13f, Ui.RED), top = 10)
            return
        }
        if (mode == 2) {
            renderSources()
            return
        }
        if (rows.length() == 0) {
            listBox.put(Ui.text(act, if (mode == 0) "لا توجد قواعد بعد." else "لا توجد معرفة بعد.", 13f, Ui.MUTED), top = 14)
            return
        }
        for (i in 0 until rows.length()) renderItem(rows.getJSONObject(i))
    }

    private fun renderItem(o: JSONObject) {
        val id = o.optLong("id")
        val active = o.optBoolean("active", true)
        val card = Ui.panel(act)
        card.setPadding(dp(16), dp(14), dp(16), dp(12))
        if (mode == 1) card.addView(Ui.text(act, o.strOr("topic"), 16f, Ui.CYAN, true))
        val body = Ui.text(act, if (mode == 0) o.strOr("text") else o.strOr("content"), 14f, if (active) Ui.TEXT else Ui.GRAY)
        card.put(body, top = if (mode == 1) 4 else 0)
        if (mode == 1 && o.strOr("keywords").isNotBlank()) {
            card.put(Ui.text(act, "كلمات: " + o.strOr("keywords"), 11f, Ui.MUTED), top = 6)
        }
        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(Toggle(act, active) { v -> toggleActive(id, v) })
        bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        val ed = IconView(act, Ic.EDIT, Ui.CYAN)
        ed.isClickable = true
        ed.setOnClickListener { edit(o) }
        bar.addView(ed, Ui.lp(act, dp(28), dp(28), end = 14))
        val del = IconView(act, Ic.TRASH, Ui.RED)
        del.isClickable = true
        del.setOnClickListener {
            Dialogs.confirm(act, "حذف", "حذف هذا العنصر نهائياً؟", "حذف") { write { s -> s.removeRow(table(), id) } }
        }
        bar.addView(del, LinearLayout.LayoutParams(dp(28), dp(28)))
        card.put(bar, top = 12)
        listBox.put(card, top = 10)
    }

    // ------------------------------------------------------------ sources
    private fun statusChip(status: String): Pair<String, Int> = when (status) {
        "ready" -> "جاهز" to Ui.GREEN
        "failed" -> "تعذّرت القراءة" to Ui.RED
        "retry" -> "إعادة محاولة تلقائية" to Ui.AMBER
        else -> "قيد القراءة" to Ui.AMBER
    }

    private fun sizeText(o: JSONObject): String {
        val parts = mutableListOf<String>()
        parts.add(SrcType.label(SrcType.of(o)))
        val chunks = o.intOr("chunk_count", 0)
        if (chunks > 0) parts.add("$chunks جزء")
        val pages = o.intOr("page_count", 0)
        if (pages > 0) parts.add("$pages صفحة")
        val chars = o.intOr("char_count", 0)
        if (chars >= 1000) parts.add("${chars / 1000} ألف حرف") else if (chars > 0) parts.add("$chars حرف")
        return parts.joinToString(" · ")
    }

    private fun ghost(label: String, onClick: () -> Unit): View {
        return Ui.ghostBtn(act, label) { onClick() }
    }

    private fun folderOf(o: JSONObject): String = o.strOr("folder_id")

    private fun renderSources() {
        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(ghost("بحث") { searchDialog() }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 6))
        bar.addView(ghost("تنظيم ذكي بالمواضيع") { organize() }, Ui.lp(act, 0, Ui.WRAP, 2f, start = 6))
        listBox.put(bar, top = 4)
        if (rows.length() == 0) {
            listBox.put(Ui.text(act, "لا توجد مراجع بعد.", 13f, Ui.MUTED), top = 14)
            return
        }
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            listBox.put(Ui.chip(act, "بحث: $query  ✕", Ui.CYAN).also { it.setOnClickListener { query = ""; render(true, "") } }, top = 10)
            var n = 0
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                if (o.strOr("title").lowercase().contains(q) || o.strOr("brief").lowercase().contains(q) || o.strOr("file_name").lowercase().contains(q)) {
                    renderSource(o)
                    n++
                }
            }
            if (n == 0) listBox.put(Ui.text(act, "لا توجد نتائج.", 13f, Ui.MUTED), top = 14)
            return
        }
        val known = HashSet<String>()
        val counts = HashMap<String, Int>()
        for (i in 0 until folders.length()) known.add(folders.getJSONObject(i).strOr("id"))
        for (i in 0 until rows.length()) {
            val f = folderOf(rows.getJSONObject(i))
            if (f.isNotBlank()) counts[f] = (counts[f] ?: 0) + 1
        }
        val open = openFolder
        if (open != null && folders.length() > 0 && !known.contains(open)) openFolder = null
        if (openFolder == null) {
            for (i in 0 until folders.length()) {
                val f = folders.getJSONObject(i)
                val n = counts[f.strOr("id")] ?: 0
                if (n > 0 || !f.optBoolean("auto", true)) renderFolder(f, n)
            }
            var first = true
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                val f = folderOf(o)
                if (f.isNotBlank() && known.contains(f)) continue
                if (first) {
                    listBox.put(Ui.text(act, "غير مصنّف", 12f, Ui.MUTED), top = 16)
                    first = false
                }
                renderSource(o)
            }
        } else {
            val fid = openFolder ?: return
            var name = ""
            for (i in 0 until folders.length()) if (folders.getJSONObject(i).strOr("id") == fid) name = folders.getJSONObject(i).strOr("name")
            val head = LinearLayout(act)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.addView(ghost("‹ كل المجلدات") { openFolder = null; render(true, "") }, Ui.lp(act, Ui.WRAP, Ui.WRAP, end = 10))
            head.addView(Ui.text(act, name, 15f, Ui.TEXT, true), Ui.lp(act, 0, Ui.WRAP, 1f))
            val ed = IconView(act, Ic.EDIT, Ui.CYAN)
            ed.isClickable = true
            ed.setOnClickListener { renameFolderDialog(fid, name) }
            head.addView(ed, Ui.lp(act, dp(28), dp(28), end = 12))
            val del = IconView(act, Ic.TRASH, Ui.RED)
            del.isClickable = true
            del.setOnClickListener {
                Dialogs.confirm(act, "حذف المجلد", "حذف المجلد «$name»؟ تبقى مراجعه كلها وتعود إلى «غير مصنّف».", "حذف") {
                    openFolder = null
                    Folders.forget()
                    write { s -> s.deleteFolder(fid) }
                }
            }
            head.addView(del, LinearLayout.LayoutParams(dp(28), dp(28)))
            listBox.put(head, top = 14)
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                if (folderOf(o) == fid) renderSource(o)
            }
        }
    }

    private fun renderFolder(f: JSONObject, n: Int) {
        val card = Ui.panel(act)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(IconView(act, Ic.FOLDER, Ui.CYAN), LinearLayout.LayoutParams(dp(24), dp(24)))
        head.addView(Ui.text(act, f.strOr("name"), 15f, Ui.TEXT, true), Ui.lp(act, 0, Ui.WRAP, 1f, start = 10))
        head.addView(Ui.chip(act, "$n", Ui.MUTED))
        card.addView(head)
        card.isClickable = true
        card.setOnClickListener { openFolder = f.strOr("id"); render(true, "") }
        listBox.put(card, top = 10)
    }

    private fun searchDialog() {
        Dialogs.form(act, "بحث في المراجع", listOf(Dialogs.FormField("كلمة من العنوان أو الوصف", query)), "بحث") { v ->
            query = v[0].trim()
            render(true, "")
        }
    }

    private fun renameFolderDialog(id: String, name: String) {
        Dialogs.form(act, "اسم المجلد", listOf(Dialogs.FormField("الاسم", name)), "حفظ") { v ->
            if (v[0].isBlank()) act.toast("الاسم فارغ")
            else {
                Folders.forget()
                write { s -> s.renameFolder(id, v[0].trim().take(40)) }
            }
        }
    }

    private fun organize() {
        val s = Repo.supa(act) ?: return
        act.toast("جارٍ تنظيم المراجع بحسب مواضيعها…")
        Thread {
            val msg = Folders.smartOrganize(act, s, Repo.personaId) { }
            act.runOnUiThread {
                act.toast(msg)
                openFolder = null
                reload()
            }
        }.start()
    }

    private fun moveDialog(o: JSONObject) {
        val id = o.strOr("id")
        val names = ArrayList<String>()
        val ids = ArrayList<String>()
        for (i in 0 until folders.length()) {
            val f = folders.getJSONObject(i)
            names.add(f.strOr("name"))
            ids.add(f.strOr("id"))
        }
        names.add("＋ مجلد جديد…")
        Dialogs.pick(act, "نقل إلى مجلد", names, -1) { i ->
            if (i < ids.size) write { s -> s.moveSources(listOf(id), ids[i], true) }
            else Dialogs.form(act, "مجلد جديد", listOf(Dialogs.FormField("اسم المجلد", "")), "إنشاء ونقل") { v ->
                if (v[0].isBlank()) act.toast("الاسم فارغ")
                else write { s ->
                    val fid = s.ensureFolder(Repo.personaId, v[0].trim().take(40), false)
                    if (fid != null) s.moveSources(listOf(id), fid, true) else false
                }
            }
        }
    }

    private fun stopPreview() {
        try { preview?.stop() } catch (e: Exception) { }
        try { preview?.release() } catch (e: Exception) { }
        preview = null
        previewId = null
    }

    private fun previewAudio(o: JSONObject) {
        if (preview != null) {
            stopPreview()
            render(true, "")
            return
        }
        act.toast("جارٍ تحميل الصوت…")
        Thread {
            val f = Drive.download(act, o.strOr("drive_file_id"))
            act.runOnUiThread {
                if (f == null) {
                    act.toast(Drive.problemText())
                    return@runOnUiThread
                }
                try {
                    val mp = android.media.MediaPlayer()
                    mp.setDataSource(f.path)
                    mp.setOnCompletionListener {
                        stopPreview()
                        render(true, "")
                    }
                    mp.prepare()
                    mp.start()
                    preview = mp
                    previewId = o.strOr("id")
                    render(true, "")
                } catch (e: Exception) {
                    act.toast("تعذّر تشغيل هذا الملف")
                }
            }
        }.start()
    }

    private fun renderSource(o: JSONObject) {
        val id = o.strOr("id")
        val kind = o.strOr("kind")
        val status = o.strOr("status")
        val type = SrcType.of(o)
        val card = Ui.panel(act)
        card.setPadding(dp(16), dp(14), dp(16), dp(12))
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(IconView(act, SrcType.icon(type), Ui.CYAN), LinearLayout.LayoutParams(dp(22), dp(22)))
        val title = o.strOr("title").ifBlank { Ingest.fallbackTitle(o.strOr("url"), o.strOr("file_name")).ifBlank { "مرجع" } }
        head.addView(Ui.text(act, title, 15f, Ui.TEXT, true), Ui.lp(act, 0, Ui.WRAP, 1f, start = 10))
        val sc = statusChip(status)
        head.addView(Ui.chip(act, sc.first, sc.second))
        card.addView(head)
        card.put(Ui.text(act, sizeText(o), 11f, Ui.MUTED), top = 6)

        val sub = when (status) {
            "ready" -> o.strOr("brief")
            "pending" -> o.strOr("progress").ifBlank { "جارٍ القراءة…" }
            else -> o.strOr("error").ifBlank { Msg.GENERIC }
        }
        if (sub.isNotBlank()) card.put(Ui.text(act, sub, 12f, if (status == "failed") Ui.RED else Ui.MUTED), top = 8)
        if (status == "ready" && o.strOr("note").isNotBlank()) card.put(Ui.text(act, o.strOr("note"), 11f, Ui.AMBER), top = 4)
        if (status == "retry") card.put(Ui.text(act, "ستُعاد المحاولة تلقائياً.", 11f, Ui.MUTED), top = 4)

        val url = o.strOr("url")
        if (url.isNotBlank()) {
            val u = Ui.text(act, url, 11f, Ui.GRAY)
            u.layoutDirection = View.LAYOUT_DIRECTION_LTR
            u.maxLines = 1
            u.ellipsize = android.text.TextUtils.TruncateAt.END
            card.put(u, top = 4)
        }

        // when something went wrong, always offer a way forward
        if ((status == "failed" || status == "retry") && kind != "supabase") {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ghost("إعادة المحاولة الآن") { Ingest.retryNow(act, id); act.toast("أعيد المحاولة…"); reload() }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 6))
            if (o.strOr("error") == Msg.NEED_DRIVE) {
                row.addView(ghost("ربط Google Drive") {
                    Drive.connect(act) { ok ->
                        if (ok) { Ingest.retryNow(act, id); reload() } else act.toast("لم يكتمل ربط Drive")
                    }
                }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 6))
            } else if (kind != "youtube") row.addView(ghost("ملف بديل") { chooseFile(id) }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 6))
            card.put(row, top = 10)
        }

        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        if (status == "ready" && kind != "supabase") {
            val info = IconView(act, Ic.SEARCH, Ui.CYAN)
            info.isClickable = true
            info.setOnClickListener { showDetails(id, title) }
            bar.addView(info, Ui.lp(act, dp(28), dp(28), end = 14))
        }
        if (status == "ready" && type == "audio" && o.strOr("drive_file_id").isNotBlank()) {
            val pl = IconView(act, if (previewId == id) Ic.PAUSE else Ic.PLAY, Ui.CYAN)
            pl.isClickable = true
            pl.setOnClickListener { previewAudio(o) }
            bar.addView(pl, Ui.lp(act, dp(28), dp(28), end = 14))
        }
        bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        if (kind != "supabase") {
            val mv = IconView(act, Ic.FOLDER, Ui.CYAN)
            mv.isClickable = true
            mv.setOnClickListener { moveDialog(o) }
            bar.addView(mv, Ui.lp(act, dp(28), dp(28), end = 14))
        }
        val rf = IconView(act, Ic.REFRESH, Ui.CYAN)
        rf.isClickable = true
        rf.setOnClickListener { refreshSource(o) }
        bar.addView(rf, Ui.lp(act, dp(28), dp(28), end = 14))
        val del = IconView(act, Ic.TRASH, Ui.RED)
        del.isClickable = true
        del.setOnClickListener {
            Dialogs.confirm(act, "حذف المصدر", "حذف «$title» من مراجع هذه الشخصية؟", "حذف") {
                write { s ->
                    if (kind == "supabase") s.deleteSecret("lib_$id")
                    try { Ingest.localFile(act, id).delete() } catch (e: Exception) { }
                    val dfid = o.strOr("drive_file_id")
                    if (dfid.isNotBlank()) Drive.delete(act, dfid)
                    s.deleteSource(id)
                }
            }
        }
        bar.addView(del, LinearLayout.LayoutParams(dp(28), dp(28)))
        card.put(bar, top = 10)
        listBox.put(card, top = 10)
    }

    /** What the agent sees of this source: how it was read and its outline (the map of parts). */
    private fun showDetails(id: String, title: String) {
        val s = Repo.supa(act) ?: return
        Thread {
            val d = s.getOutline(id)
            act.runOnUiThread {
                if (d == null) {
                    act.toast(s.lastError)
                    return@runOnUiThread
                }
                val via = when (d.strOr("via")) {
                    "direct" -> "قراءة مباشرة من الموقع"
                    "reader" -> "عبر قارئ احتياطي"
                    "archive" -> "من نسخة مؤرشفة"
                    "gemini" -> "قُرئ بالذكاء الاصطناعي"
                    "file" -> "ملف مرفوع"
                    else -> ""
                }
                val sb = StringBuilder()
                if (via.isNotBlank()) sb.append(via).append("\n\n")
                val ol = d.strOr("outline")
                sb.append(if (ol.isBlank()) "لا يوجد فهرس لهذا المرجع." else "فهرس المحتوى:\n$ol")
                Dialogs.message(act, title, sb.toString())
            }
        }.start()
    }

    private fun add() {
        if (!Repo.canEdit(act)) {
            act.toast(Repo.READ_ONLY)
            return
        }
        if (mode == 2) {
            val kinds = listOf(
                "رابط (مقال، صفحة، PDF، يوتيوب)",
                "موقع أو توثيق كامل",
                "ملفات من الجهاز"
            )
            Dialogs.pick(act, "إضافة مرجع", kinds, -1) { i ->
                when (i) {
                    0 -> linkForm(false)
                    1 -> linkForm(true)
                    else -> chooseFiles()
                }
            }
        } else edit(null)
    }

    /** One path for every file: pick (one or many) → [Drive permission, only the first time and only for audio/images] → title and description → background work. */
    private fun chooseFiles() {
        act.pickFiles(false) { uris -> afterPick(uris) }
    }

    private fun afterPick(uris: List<android.net.Uri>) {
        val media = uris.any { u ->
            val m = act.contentResolver.getType(u) ?: ""
            m.startsWith("audio/") || m.startsWith("image/")
        }
        if (media && !Drive.connected(act)) {
            Dialogs.confirm(
                act, "التخزين على Google Drive",
                "تُحفظ الصوتيات والصور في مجلد TikGuest داخل حسابك على Google Drive، ولا يصل التطبيق إلى غير ما ينشئه.", "متابعة"
            ) {
                Drive.connect(act) { ok -> if (ok) importFiles(uris) else act.toast("لم يكتمل الربط، يمكنك المحاولة لاحقاً") }
            }
        } else importFiles(uris)
    }

    private fun importFiles(uris: List<android.net.Uri>) {
        if (uris.size == 1) {
            val stem = Ingest.displayName(act, uris[0]).substringBeforeLast('.')
            Dialogs.form(
                act, "تفاصيل المرجع",
                listOf(Dialogs.FormField("العنوان", stem), Dialogs.FormField("الوصف (اختياري)", "", true)), "استيراد"
            ) { v -> startImport(uris, v[0].trim(), v[1].trim()) }
        } else startImport(uris, "", "")
    }

    private fun startImport(uris: List<android.net.Uri>, title: String, desc: String) {
        act.toast("جارٍ استيراد ${uris.size} ملف")
        Ingest.addFiles(act, uris, title, desc) { msg ->
            act.runOnUiThread {
                act.toast(msg)
                reload()
            }
        }
    }

    private fun linkForm(site: Boolean) {
        val title = if (site) "موقع أو توثيق كامل" else "رابط مرجعي"
        Dialogs.form(act, title, listOf(Dialogs.FormField("العنوان (اختياري)", ""), Dialogs.FormField(if (site) "رابط الصفحة الرئيسية للموقع أو التوثيق" else "الرابط", "")), "إضافة وقراءة") { v ->
            if (v[1].isBlank()) act.toast("الرابط فارغ")
            else Ingest.addLink(act, v[0], v[1], site) { err ->
                act.runOnUiThread {
                    act.toast(err ?: "بدأت القراءة، يمكنك المتابعة")
                    reload()
                }
            }
        }
    }

    private fun chooseFile(replaceId: String?) {
        act.pickFile { uri ->
            act.toast("جارٍ نسخ الملف…")
            Ingest.addFile(act, uri, replaceId) { err ->
                act.runOnUiThread {
                    if (err != null) act.toast(err)
                    reload()
                }
            }
        }
    }

    private fun libForm() {
        Dialogs.form(
            act, "مكتبة Supabase",
            listOf(
                Dialogs.FormField("اسم المكتبة", ""),
                Dialogs.FormField("رابط المشروع (https://xxxx.supabase.co)", ""),
                Dialogs.FormField("مفتاح القراءة العام (anon أو publishable)", ""),
                Dialogs.FormField("اسم الجدول", ""),
                Dialogs.FormField("عمود العنوان", ""),
                Dialogs.FormField("عمود النص (اختياري)", ""),
                Dialogs.FormField("أعمدة البحث مفصولة بفاصلة (اختياري)", ""),
                Dialogs.FormField("عمود الرابط (اختياري)", "")
            ),
            "ربط المكتبة"
        ) { v ->
            if (v[1].isBlank() || v[2].isBlank() || v[3].isBlank() || v[4].isBlank()) act.toast("أكمل الرابط والمفتاح والجدول وعمود العنوان")
            else createLib(v)
        }
    }

    private fun createLib(v: List<String>) {
        val s = Repo.supa(act) ?: return
        val cfg = JSONObject().put("table", v[3]).put("titleCol", v[4]).put("textCol", v[5]).put("searchCols", v[6]).put("urlCol", v[7])
        Thread {
            val id = s.createSource(
                JSONObject().put("persona_id", Repo.personaId).put("kind", "supabase").put("type", "library").put("title", v[0].ifBlank { "مكتبتي" })
                    .put("url", v[1].trim()).put("config", cfg).put("status", "pending")
            )
            if (id == null) {
                act.runOnUiThread { act.toast(s.lastError); reload() }
                return@Thread
            }
            s.saveSecret("lib_$id", v[2].trim())
            refreshLibrary(s, id, v[1].trim(), v[2].trim(), cfg)
        }.start()
    }

    private fun refreshLibrary(s: Supa, id: String, url: String, key: String, cfg: JSONObject) {
        val r = Sources.libraryCatalog(url, key, cfg)
        val d = r.first
        val patch = if (d != null) JSONObject().put("digest", d).put("brief", Sources.brief(d)).put("status", "ready").put("error", "")
        else JSONObject().put("status", "failed").put("error", r.second)
        s.updateSource(id, patch)
        act.runOnUiThread { act.toast(if (d != null) "تم ربط المكتبة" else r.second); reload() }
    }

    private fun refreshSource(o: JSONObject) {
        val s = Repo.supa(act) ?: return
        val id = o.strOr("id")
        val kind = o.strOr("kind")
        if (kind == "file") {
            // the local copy is deleted once the file is stored: refreshing means choosing the (new) file again
            chooseFile(id)
            return
        }
        act.toast("جارٍ التحديث...")
        if (kind == "supabase") {
            Thread {
                s.updateSource(id, JSONObject().put("status", "pending").put("error", ""))
                act.runOnUiThread { reload() }
                val key = s.getSecret("lib_$id") ?: ""
                val cfg = try { JSONObject(o.optString("config", "{}")) } catch (e: Exception) { o.optJSONObject("config") ?: JSONObject() }
                refreshLibrary(s, id, o.strOr("url"), key, cfg)
            }.start()
        } else {
            Ingest.retryNow(act, id)
            reload()
        }
    }

    // ------------------------------------------------------------ rules and knowledge
    private fun edit(o: JSONObject?) {
        val fields = if (mode == 0) listOf(
            Dialogs.FormField("نص القاعدة", o?.strOr("text") ?: "", true)
        ) else listOf(
            Dialogs.FormField("الموضوع", o?.strOr("topic") ?: ""),
            Dialogs.FormField("المحتوى", o?.strOr("content") ?: "", true),
            Dialogs.FormField("كلمات مفتاحية للبحث (اختياري)", o?.strOr("keywords") ?: "")
        )
        Dialogs.form(act, if (o == null) "إضافة" else "تعديل", fields) { v ->
            val row = JSONObject().put("persona_id", Repo.personaId)
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

    private fun write(op: (Supa) -> Any) {
        if (!Repo.canEdit(act)) {
            act.toast(Repo.READ_ONLY)
            return
        }
        val s = Repo.supa(act) ?: return
        Thread {
            val res = op(s)
            val ok = (res as? Pair<*, *>)?.first as? Boolean ?: true
            act.runOnUiThread {
                if (!ok) act.toast(s.lastError)
                reload()
            }
        }.start()
    }
}
