package app.dayf

import android.content.Context
import org.json.JSONObject

/**
 * play_audio / stop_audio / audio_status, shared by the real agent and the studio screen so both behave the same.
 * The audio list (notes + files) is prepared when the session starts, so a request starts playing at once.
 */
class AudioTools(
    private val ctx: Context,
    private val supaOf: () -> Supa?,
    private val personaOf: () -> String,
    private val playerOf: () -> AgentPlayer?,
    private val liveOf: () -> LiveClient?
) {
    companion object {
        val NAMES = setOf("play_audio", "stop_audio", "audio_status", "pause_audio", "resume_audio")
    }

    private class Item(val id: String, val title: String, val driveId: String, val secs: Int, val outline: String, val notes: String)

    @Volatile private var items: List<Item> = emptyList()

    fun warm() {
        MediaOut.notifyAgent = { t -> liveOf()?.sendText(t) }
        val s = supaOf() ?: return
        val pid = personaOf()
        if (pid.isBlank()) return
        Thread {
            try {
                val rows = s.audioSources(pid)
                val list = ArrayList<Item>()
                for (i in 0 until minOf(rows.length(), 12)) {
                    val o = rows.getJSONObject(i)
                    val did = o.strOr("drive_file_id")
                    if (did.isBlank()) continue
                    val id = o.strOr("id")
                    val total = o.intOr("chunk_count", 0)
                    val sb = StringBuilder()
                    if (total > 0) for (ck in s.chunkRange(id, 0, minOf(total - 1, 2))) sb.append(ck.text).append("\n\n")
                    list.add(Item(id, o.strOr("title"), did, o.intOr("media_secs", 0), o.strOr("outline"), sb.toString().take(3000)))
                }
                items = list
                for (it in list) Drive.download(ctx, it.driveId)
                AppLog.add("الصوتيات الجاهزة للبث: ${list.size}")
            } catch (e: Exception) {
                AppLog.add("تجهيز الصوتيات: ${e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun resolve(s: Supa, name: String): Item? {
        val cached = items
        if (cached.isNotEmpty()) {
            if (name.isBlank()) cached.singleOrNull()?.let { return it }
            val ws = Sources.words(name).map { Ar.norm(it) }
            var best: Item? = null
            var bs = 0
            for (it in cached) {
                val t = Ar.norm(it.title)
                val sc = ws.count { w -> t.contains(w) }
                if (sc > bs) { bs = sc; best = it }
            }
            if (best != null) return best
            if (cached.size == 1) return cached[0]
        }
        val src = s.findSource(personaOf(), name, "audio") ?: return null
        val fid = src.strOr("drive_file_id")
        if (fid.isBlank()) return null
        val total = src.intOr("chunk_count", 0)
        val sb = StringBuilder()
        if (total > 0) for (ck in s.chunkRange(src.strOr("id"), 0, minOf(total - 1, 2))) sb.append(ck.text).append("\n\n")
        return Item(src.strOr("id"), src.strOr("title"), fid, src.intOr("media_secs", 0), src.strOr("outline"), sb.toString().take(3000))
    }

    private fun failed(res: JSONObject, msg: String): JSONObject =
        res.put("played", false).put("message", msg).put("instruction", "لا تكرر جملة التمهيد. اعتذر بجملة قصيرة طبيعية وتابع الحديث.")

    fun handle(name: String, args: JSONObject): JSONObject {
        UiState.lastTool = name
        UiState.lastToolAt = System.currentTimeMillis()
        AppLog.add("أداة $name ← ${args.toString().take(100)}")
        val res = JSONObject()
        val s = supaOf()
        val p = playerOf()
        if (s == null || p == null || personaOf().isBlank()) return res.put("error", "not ready")
        try {
            when (name) {
                "play_audio" -> {
                    val item = resolve(s, args.optString("source"))
                    if (item == null) {
                        AppLog.add("بثّ صوت: لا مرجع صوتي باسم «${args.optString("source")}»")
                        return failed(res, "لا يوجد مرجع صوتي بهذا الاسم. الصوتيات المتاحة: " + s.audioTitles(personaOf()))
                    }
                    val from = args.optInt("from_seconds", 0).coerceIn(0, maxOf(0, item.secs - 1))
                    if (MediaOut.playing && MediaOut.srcId == item.id && from == 0) {
                        return res.put("playing", true).put("title", item.title).put("instruction", "هذا الصوت يُبثّ الآن بالفعل. لا تكرر الاستدعاء ولا تتكلم أثناءه.")
                    }
                    val f = Drive.download(ctx, item.driveId)
                    if (f == null) {
                        AppLog.add("بثّ صوت: ${Drive.problemText()}")
                        return failed(res, Drive.problemText())
                    }
                    val ok = MediaOut.play(p, f, from, item.title, item.id, item.secs, item.outline) { completed ->
                        if (completed) {
                            AppLog.add("بثّ صوت: انتهى «${item.title}»")
                            liveOf()?.sendText("[تعليمات المخرج] انتهى بثّ الصوت «${item.title}». علّق عليه بجملة أو جملتين من محتواه ثم تابع الحديث بطبيعية.")
                        }
                    }
                    if (!ok) {
                        AppLog.add("بثّ صوت: تعذّر فك ترميز الملف «${item.title}»")
                        return failed(res, "تعذّر تشغيل هذا الملف الصوتي")
                    }
                    AppLog.add("بثّ صوت: بدأ «${item.title}» من الثانية $from")
                    res.put("playing", true).put("title", item.title).put("duration_s", item.secs).put("from_s", from)
                        .put("notes_start", item.notes).put("outline", item.outline.take(1500))
                        .put("instruction", "الصوت يُبثّ الآن للحاضرين. لا تتكلم أثناءه ولا تكرر أي جملة. ما سبق هو ملاحظات محتواه لتعرف ما يُبثّ، وسيصلك إشعار عند انتهائه.")
                }
                "pause_audio" -> {
                    val pos = MediaOut.pause()
                    res.put("paused", pos >= 0).put("position_s", pos)
                        .put("instruction", "الصوت متوقف مؤقتاً. تكلم أو استمع للمضيف، ثم استأنفه بـ resume_audio حين يطلب المتابعة.")
                }
                "resume_audio" -> {
                    val ok = MediaOut.resume()
                    res.put("resumed", ok)
                    if (ok) res.put("instruction", "الصوت يعمل الآن. لا تتكلم أثناءه.")
                    else res.put("message", "لا يوجد صوت متوقف لاستئنافه")
                }
                "stop_audio" -> {
                    val was = MediaOut.playing
                    MediaOut.stop()
                    res.put("stopped", was)
                }
                else -> {
                    val pos = MediaOut.positionSecs()
                    res.put("playing", MediaOut.playing).put("title", MediaOut.title).put("position_s", pos).put("duration_s", MediaOut.durationSecs)
                    if (MediaOut.playing) {
                        MediaOut.partAt(pos)?.let { part ->
                            s.chunkRange(MediaOut.srcId, part - 1, part - 1).firstOrNull()?.let { res.put("current_section", it.text.take(1500)) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.add("أداة الصوت: ${e.javaClass.simpleName} ${e.message}")
            res.put("error", "failed")
        }
        return res
    }
}
