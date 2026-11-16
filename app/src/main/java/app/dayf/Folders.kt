package app.dayf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Folders for the sources: automatic by type while reading, topical on request ("smart organize"), manual always wins. */
object Folders {
    private val cache = HashMap<String, String>()

    fun nameFor(type: String) = when (type) {
        "article", "docs", "site" -> "المقالات والمواقع"
        "book" -> "الكتب"
        "document" -> "المستندات"
        "sheet" -> "الجداول"
        "video" -> "الفيديوهات"
        "audio" -> "الصوتيات"
        "image" -> "الصور"
        "text" -> "النصوص"
        "library" -> "المكتبات"
        else -> "مراجع أخرى"
    }

    @Synchronized
    fun idFor(s: Supa, pid: String, name: String, auto: Boolean = true): String? {
        val k = "$pid|$name"
        cache[k]?.let { return it }
        val id = s.ensureFolder(pid, name, auto) ?: return null
        cache[k] = id
        return id
    }

    @Synchronized
    fun forget() = cache.clear()

    /**
     * One request that asks Gemini to group the not-manually-placed sources by subject (never by file type).
     * Titles and short briefs only, so even hundreds of sources cost very little.
     */
    fun smartOrganize(ctx: Context, s: Supa, pid: String, progress: (String) -> Unit): String {
        val key = Secrets.gemini(ctx)
        if (key.isBlank()) return Msg.NEED_KEY
        val rows = s.listSources(pid) ?: return s.lastError
        val items = ArrayList<JSONObject>()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            if (o.strOr("status") == "ready" && !o.optBoolean("folder_manual", false)) items.add(o)
        }
        if (items.size < 4) return "عدد المراجع قليل للتنظيم بالمواضيع"
        val models = Ingest.models(ctx, key)
        if (models.isEmpty()) return Msg.NEED_KEY
        val used = LinkedHashSet<String>()
        var moved = 0
        val batches = items.chunked(100)
        for ((bi, batch) in batches.withIndex()) {
            progress("تنظيم المراجع ${bi + 1}/${batches.size}…")
            val list = StringBuilder()
            batch.forEachIndexed { i, o ->
                list.append(i + 1).append(" | ").append(SrcType.label(SrcType.of(o))).append(" | ")
                    .append(o.strOr("title").take(80)).append(" | ").append(o.strOr("brief").take(70)).append('\n')
            }
            val prompt = "You organise a library of reference sources into folders by SUBJECT (never by file type). " +
                "Create between 3 and 10 folders for these ${batch.size} items (fewer if there are few items), each with a short Arabic name of 1 to 3 words naming the subject. " +
                "Every item must be in exactly one folder. " + (if (used.isNotEmpty()) "Reuse these existing folder names when an item fits: ${used.joinToString(", ")}. " else "") +
                "Reply with JSON only, in this exact shape: {\"folders\":[{\"name\":\"...\",\"items\":[1,2]}]}\n\nItems (number | type | title | brief):\n$list"
            val o = Gem.generate(key, models, listOf(Gem.textPart(prompt)), 8000, null, 3, true)
            if (!o.ok) return if (o.code == 0 || Net.retryable(o.code)) Msg.BUSY else Msg.GENERIC
            try {
                val arr: JSONArray = JSONObject(o.text.trim().removePrefix("```json").removeSuffix("```").trim()).getJSONArray("folders")
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val name = f.optString("name").trim().take(40)
                    if (name.isBlank()) continue
                    val ids = ArrayList<String>()
                    val its = f.optJSONArray("items") ?: continue
                    for (k in 0 until its.length()) {
                        val n = its.optInt(k, 0)
                        if (n in 1..batch.size) ids.add(batch[n - 1].strOr("id"))
                    }
                    if (ids.isEmpty()) continue
                    val fid = idFor(s, pid, name, true) ?: continue
                    used.add(name)
                    if (s.moveSources(ids, fid, false)) moved += ids.size
                }
            } catch (e: Exception) {
                AppLog.add("تنظيم ذكي: ردّ غير مفهوم (${e.javaClass.simpleName})")
                return Msg.GENERIC
            }
        }
        return if (moved > 0) "تم تنظيم $moved مرجعاً في ${used.size} مجلد" else Msg.GENERIC
    }
}
