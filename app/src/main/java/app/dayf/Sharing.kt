package app.dayf

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import app.dayf.Ui.put

/**
 * Sharing a persona with other people, in two kinds of invitation:
 *  - "edit" (collaboration): the same persona, the same rows; every member may edit and everybody sees the changes.
 *  - "use": members talk with the persona but cannot change it.
 * The owner can switch an invitation (and everyone who joined through it) between the two at any time.
 * A member can clone the persona into an independent copy of their own; cloning ends their membership of the original.
 */
object Sharing {
    fun host(ctx: Context): String {
        val id = ctx.resources.getIdentifier("share_host", "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else "tik.orcaup.com"
    }

    fun linkFor(ctx: Context, code: String) = "https://${host(ctx)}/s/$code"

    /** Media uploaded to a shared persona must be reachable by the other members, whose accounts cannot see this Drive. */
    fun onMediaUploaded(ctx: Context, s: Supa, driveId: String) {
        try {
            if (driveId.isNotBlank() && s.isShared(Repo.personaId)) Drive.makePublic(ctx, driveId)
        } catch (e: Exception) {
        }
    }

    private fun kindLabel(mode: String) = if (mode == "edit") "تعاون" else "استخدام فقط"

    // ------------------------------------------------------------------ owner: invitations
    fun startShare(act: MainActivity) {
        if (!Repo.isOwner(act)) {
            act.toast("الدعوات من صلاحيات مالك الشخصية.")
            return
        }
        Dialogs.pick(
            act, "نوع الدعوة",
            listOf(
                "تعاون: تحرير مشترك، وكل تغيير يظهر عند الجميع",
                "استخدام فقط: الحديث بالشخصية دون تعديلها"
            ), -1
        ) { i -> create(act, if (i == 0) "edit" else "use") }
    }

    private fun create(act: MainActivity, mode: String) {
        val s = Repo.supa(act) ?: return
        val pid = Repo.personaId
        val title = Repo.cfg().title
        act.toast("جارٍ إنشاء الدعوة")
        Thread {
            val code = s.createShare(pid, mode, "")
            if (code == null) {
                act.runOnUiThread { act.toast(s.lastError) }
                return@Thread
            }
            shareMedia(act, s, pid)
            act.runOnUiThread { send(act, title, code, mode) }
        }.start()
    }

    /** Audio and images live in the owner's Drive: let anyone holding the link read them so other members can fetch them. */
    private fun shareMedia(ctx: Context, s: Supa, pid: String) {
        if (!Drive.connected(ctx)) return
        val rows = s.listSources(pid) ?: return
        for (i in 0 until rows.length()) {
            val id = rows.getJSONObject(i).strOr("drive_file_id")
            if (id.isNotBlank()) Drive.makePublic(ctx, id)
        }
    }

    fun send(act: MainActivity, title: String, code: String, mode: String) {
        val kind = if (mode == "edit") "تعاون (تحرير مشترك)" else "استخدام فقط"
        val text = "دعوة إلى الشخصية «$title» في Tik Guest\nالنوع: $kind\n\n${linkFor(act, code)}\n\nرمز الدعوة: $code"
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        act.startActivity(Intent.createChooser(i, "إرسال الدعوة"))
    }

    // ------------------------------------------------------------------ receiving an invitation
    fun acceptDialog(act: MainActivity, prefill: String = "") {
        Dialogs.form(act, "قبول دعوة", listOf(Dialogs.FormField("رمز الدعوة", prefill)), "متابعة") { v ->
            val code = v[0].trim().uppercase()
            if (code.isBlank()) act.toast("اكتب رمز الدعوة") else preview(act, code)
        }
    }

    private fun invalid(s: Supa) = s.lastRaw.contains("invalid") || s.lastRaw.isBlank()

    private fun preview(act: MainActivity, code: String) {
        val s = Repo.supa(act) ?: return
        act.toast("جارٍ التحقق من الدعوة")
        Thread {
            val p = s.previewShare(code)
            act.runOnUiThread {
                if (p == null) {
                    act.toast(if (invalid(s)) "الدعوة غير صالحة أو أُوقفت" else s.lastError)
                    return@runOnUiThread
                }
                if (p.optBoolean("already", false)) {
                    act.toast("هذه الشخصية ضمن شخصياتك بالفعل")
                    return@runOnUiThread
                }
                val mode = p.strOr("mode")
                val edit = mode == "edit"
                val msg = "من ${p.strOr("owner_name")}\n" +
                    "${p.intOr("rules_n", 0)} قاعدة · ${p.intOr("knowledge_n", 0)} معلومة · ${p.intOr("sources_n", 0)} مرجع\n\n" +
                    when (mode) {
                        "edit" -> "الصلاحية: تعاون. يمكنك التعديل ويظهر لدى الجميع."
                        "copy" -> "ستحصل على نسخة مستقلة تعدّلها وحدك."
                        else -> "الصلاحية: استخدام فقط."
                    }
                Dialogs.confirm(act, p.strOr("title", p.strOr("name")), msg, if (mode == "copy") "إضافة نسخة" else "قبول الدعوة") {
                    accept(act, code, mode, p.intOr("sources_n", 0) > 0, edit)
                }
            }
        }.start()
    }

    private fun accept(act: MainActivity, code: String, mode: String, hasSources: Boolean, edit: Boolean) {
        if (mode == "copy" && hasSources && !Drive.connected(act)) {
            Dialogs.confirm(act, "التخزين على Google Drive", "لنسخ الصوتيات والصور إلى حسابك نحتاج إلى إذنك مرة واحدة.", "متابعة") {
                Drive.connect(act) { run(act, code) }
            }
        } else run(act, code)
    }

    private fun run(act: MainActivity, code: String) {
        val s = Repo.supa(act) ?: return
        act.toast("جارٍ الانضمام")
        Thread {
            val r = s.acceptShare(code)
            if (r == null) {
                act.runOnUiThread { act.toast(if (invalid(s)) "الدعوة غير صالحة أو أُوقفت" else s.lastError) }
                return@Thread
            }
            val pid = r.optString("persona_id")
            if (r.optString("mode") == "copy") finishClone(act, s, null, r)
            act.runOnUiThread {
                Repo.switchTo(act, pid) { ok, msg ->
                    act.toast(if (ok) "تمت إضافة الشخصية" else msg)
                    if (ok) act.onPersonaChanged()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ member: clone and become independent
    fun cloneAndLeave(act: MainActivity) {
        val s = Repo.supa(act) ?: return
        val src = Repo.personaId
        Dialogs.confirm(
            act, "استنساخ الشخصية",
            "تحصل على نسخة مستقلة تملكها وتعدّلها وحدك، وتنتهي مشاركتك مع الشخصية الأصلية.", "استنساخ"
        ) {
            act.toast("جارٍ الاستنساخ")
            Thread {
                val r = s.clonePersona(src)
                if (r == null) {
                    act.runOnUiThread { act.toast(s.lastError) }
                    return@Thread
                }
                finishClone(act, s, src, r)
                s.leavePersona(src)
                val np = r.optString("persona_id")
                act.runOnUiThread {
                    Repo.switchTo(act, np) { ok, msg ->
                        act.toast(if (ok) "أصبحت النسخة مستقلة عنك وحدك" else msg)
                        if (ok) act.onPersonaChanged()
                    }
                }
            }.start()
        }
    }

    /** The rows are cloned on the server; chunks and files follow here in small steps so no request runs long. */
    private fun finishClone(act: MainActivity, s: Supa, src: String?, r: JSONObject) {
        val srcId = src ?: ""
        val pairs = r.optJSONArray("pairs") ?: JSONArray()
        val total = pairs.length()
        if (srcId.isNotBlank()) {
            var i = 0
            while (i < total) {
                val batch = JSONArray()
                for (k in i until minOf(total, i + 8)) batch.put(pairs.getJSONObject(k))
                val n = minOf(total, i + 8)
                act.runOnUiThread { act.toast("نسخ المراجع $n/$total") }
                if (!s.cloneChunks(srcId, batch)) {
                    Net.pause(1)
                    if (!s.cloneChunks(srcId, batch)) AppLog.add("استنساخ: تعذّر نسخ دفعة من المراجع")
                }
                i += 8
            }
        }
        val media = r.optJSONArray("media") ?: JSONArray()
        if (media.length() > 0 && Drive.connected(act)) {
            for (k in 0 until media.length()) {
                val m = media.getJSONObject(k)
                val fid = m.optString("file")
                act.runOnUiThread { act.toast("نسخ الملفات ${k + 1}/${media.length()}") }
                try {
                    val dest = File(File(act.cacheDir, "media").apply { mkdirs() }, "cp_" + fid.filter { it.isLetterOrDigit() || it == '_' || it == '-' })
                    val f = Drive.downloadPublic(act, fid, dest) ?: continue
                    val nid = Drive.upload(act, f, m.optString("name").ifBlank { "media" }, m.optString("mime").ifBlank { "audio/mpeg" }) ?: continue
                    s.updateSource(m.optString("id"), JSONObject().put("drive_file_id", nid))
                    f.delete()
                } catch (e: Exception) {
                    AppLog.add("استنساخ ملف: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    // ------------------------------------------------------------------ the panel in the persona screen
    class Panel(private val act: MainActivity) {
        val view = Ui.panel(act)
        private val list = LinearLayout(act)

        init {
            list.orientation = LinearLayout.VERTICAL
            view.addView(Ui.text(act, "المشاركة", 15f, Ui.TEXT, true))
            view.put(Ui.text(act, "ادعُ آخرين إلى هذه الشخصية للتعاون في تحريرها أو لاستخدامها فقط.", 12f, Ui.MUTED), top = 4)
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(Ui.iconGhost(act, Ic.LINK, "دعوة", Ui.CYAN, 13f, 10) { startShare(act) }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 6))
            row.addView(Ui.iconGhost(act, Ic.PLUS, "قبول دعوة", Ui.TEXT, 13f, 10) { acceptDialog(act) }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 6))
            view.put(row, top = 12)
            view.put(list, top = 6)
        }

        fun refresh() {
            val s = Repo.supa(act) ?: return
            val pid = Repo.personaId
            val owner = Repo.isOwner(act)
            Thread {
                val role = if (owner) "owner" else s.myRole(pid)
                Repo.role = role
                val shares = if (owner) s.listShares(pid) else JSONArray()
                val members = if (owner) s.members(pid) else JSONArray()
                act.runOnUiThread { draw(owner, role, shares, members) }
            }.start()
        }

        private fun draw(owner: Boolean, role: String, shares: JSONArray, members: JSONArray) {
            list.removeAllViews()
            if (!owner) {
                val txt = if (role == "edit") "أنت عضو في هذه الشخصية بصلاحية التعاون." else "أنت عضو في هذه الشخصية بصلاحية الاستخدام فقط."
                list.put(Ui.text(act, txt, 12f, Ui.AMBER), top = 8)
                val r = LinearLayout(act)
                r.orientation = LinearLayout.HORIZONTAL
                r.addView(Ui.iconGhost(act, Ic.FILE, "استنساخ واستقلال", Ui.CYAN, 12f, 8) { cloneAndLeave(act) }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 4))
                r.addView(Ui.iconGhost(act, Ic.CROSS, "مغادرة", Ui.RED, 12f, 8) { leave() }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 4))
                list.put(r, top = 8)
                return
            }
            for (i in 0 until shares.length()) {
                val o = shares.getJSONObject(i)
                val id = o.strOr("id")
                val code = o.strOr("code")
                val mode = if (o.strOr("mode") == "edit") "edit" else "use"
                val box = LinearLayout(act)
                box.orientation = LinearLayout.VERTICAL
                box.addView(Ui.text(act, "${kindLabel(mode)} · ${code} · ${o.intOr("uses", 0)} انضمام", 12f, Ui.TEXT))
                val r1 = LinearLayout(act)
                r1.orientation = LinearLayout.HORIZONTAL
                r1.addView(Ui.iconGhost(act, Ic.LINK, "إرسال", Ui.CYAN, 12f, 8) { send(act, Repo.cfg().title, code, mode) }, Ui.lp(act, 0, Ui.WRAP, 1f, end = 4))
                r1.addView(Ui.iconGhost(act, Ic.CROSS, "إيقاف", Ui.RED, 12f, 8) { stop(id) }, Ui.lp(act, 0, Ui.WRAP, 1f, start = 4))
                box.put(r1, top = 6)
                box.put(
                    Ui.iconGhost(act, Ic.EDIT, if (mode == "edit") "تحويل إلى استخدام فقط" else "السماح بالتعاون", Ui.TEXT, 12f, 8) { switchMode(id, if (mode == "edit") "use" else "edit") },
                    top = 6
                )
                list.put(box, top = 12)
            }
            if (members.length() > 0) {
                list.put(Ui.text(act, "الأعضاء", 12f, Ui.MUTED), top = 14)
                for (i in 0 until members.length()) {
                    val m = members.getJSONObject(i)
                    val line = LinearLayout(act)
                    line.orientation = LinearLayout.HORIZONTAL
                    line.gravity = android.view.Gravity.CENTER_VERTICAL
                    val nm = m.strOr("display").ifBlank { "عضو" }
                    line.addView(Ui.text(act, "$nm · ${kindLabel(m.strOr("role"))}", 12f, Ui.TEXT), Ui.lp(act, 0, Ui.WRAP, 1f))
                    val uid = m.strOr("user_id")
                    line.addView(Ui.iconGhost(act, Ic.CROSS, "إزالة", Ui.RED, 11f, 6) { removeMember(uid, nm) })
                    list.put(line, top = 6)
                }
            }
        }

        private fun switchMode(shareId: String, mode: String) {
            val s = Repo.supa(act) ?: return
            Thread {
                val ok = s.setShareMode(shareId, mode)
                act.runOnUiThread {
                    act.toast(if (ok) (if (mode == "use") "أصبحت الدعوة للاستخدام فقط" else "أصبحت الدعوة تعاونية") else s.lastError)
                    refresh()
                }
            }.start()
        }

        private fun removeMember(userId: String, name: String) {
            val s = Repo.supa(act) ?: return
            Dialogs.confirm(act, "إزالة عضو", "إزالة «$name» من هذه الشخصية؟ تبقى تعديلاته.", "إزالة") {
                Thread {
                    val ok = s.removeMember(Repo.personaId, userId)
                    act.runOnUiThread {
                        act.toast(if (ok) "أُزيل العضو" else s.lastError)
                        refresh()
                    }
                }.start()
            }
        }

        private fun leave() {
            val s = Repo.supa(act) ?: return
            Dialogs.confirm(act, "مغادرة الشخصية", "لن تظهر لك بعد الآن، وتبقى عند مالكها.", "مغادرة") {
                Thread {
                    s.leavePersona(Repo.personaId)
                    Repo.load(act) { act.onPersonaChanged() }
                }.start()
            }
        }

        private fun stop(shareId: String) {
            val s = Repo.supa(act) ?: return
            Dialogs.confirm(act, "إيقاف الدعوة", "يتوقف الرمز فوراً ويفقد الأعضاء الذين انضموا به وصولهم إلى الشخصية.", "إيقاف") {
                Thread {
                    val ok = s.revokeShare(shareId)
                    if (ok && !s.hasShares(Repo.personaId)) {
                        val rows = s.listSources(Repo.personaId)
                        if (rows != null) for (i in 0 until rows.length()) {
                            val fid = rows.getJSONObject(i).strOr("drive_file_id")
                            if (fid.isNotBlank()) Drive.removePublic(act, fid)
                        }
                    }
                    act.runOnUiThread {
                        act.toast(if (ok) "أُوقفت الدعوة" else s.lastError)
                        refresh()
                    }
                }.start()
            }
        }
    }
}
