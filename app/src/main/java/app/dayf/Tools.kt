package app.dayf

import org.json.JSONArray
import org.json.JSONObject

/** Tools the model may call. The database is the only source of facts. Nothing about third parties is stored unless the user opts in. */
object Tools {
    private val libKeys = HashMap<String, String>()

    private fun JSONArray.add3(t: Triple<String, String, String>) {
        put(JSONObject().put("title", t.first).put("excerpt", t.second).put("link", t.third))
    }

    /** Nothing found: the question is logged here (no second tool round-trip) and the model is told how to answer naturally, once. */
    private fun unknown(res: JSONObject, s: Supa, c: Cfg, pid: String, q: String) {
        if (c.storeMemory && q.isNotBlank()) {
            try { s.addUnanswered(pid, q) } catch (e: Exception) { }
        }
        res.put("found", false).put(
            "instruction",
            "لا توجد معلومة في مصادرك. قل مرة واحدة فقط وبصياغتك الطبيعية معنى: «${c.unknownReply}» ثم تابع الحديث بسلاسة بسؤال أو تعليق قصير يخص الموضوع. لا تكرر الجملة ولا تصمت بعدها. السؤال سُجّل تلقائياً ولا حاجة لـ report_unknown."
        )
    }

    fun exec(s: Supa?, c: Cfg?, pid: String, host: String, name: String, args: JSONObject): JSONObject {
        val res = JSONObject()
        try {
            if (s == null || c == null || pid.isBlank()) {
                res.put("error", "no database")
            } else when (name) {
                "lookup_knowledge" -> {
                    val q = args.optString("query")
                    // the three searches are independent: run them together so the agent answers without a long pause
                    var rows = emptyList<Pair<String, String>>()
                    var hits = emptyList<ChunkHit>()
                    var legacy = emptyList<Pair<String, String>>()
                    val ts = listOf(
                        Thread { rows = s.search(pid, q) },
                        Thread { hits = s.searchChunks(pid, Sources.words(q), 4) },
                        Thread { legacy = s.searchSources(pid, q) }
                    )
                    ts.forEach { it.start() }
                    ts.forEach { it.join(12000) }
                    if (hits.isNotEmpty()) legacy = emptyList()
                    if (rows.isEmpty() && hits.isEmpty() && legacy.isEmpty()) {
                        // the question may name a source (e.g. an audio clip) without sharing words with its text: answer from its opening parts
                        val src = s.findSource(pid, q)
                        if (src != null && src.intOr("chunk_count", 0) > 0) {
                            hits = s.chunkRange(src.strOr("id"), 0, 1).map { ChunkHit(src.strOr("id"), src.strOr("title"), it.idx, it.heading, it.page, it.text) }
                        }
                    }
                    if (rows.isEmpty() && hits.isEmpty() && legacy.isEmpty()) {
                        unknown(res, s, c, pid, q)
                    } else {
                        val arr = JSONArray()
                        rows.forEach { arr.put(JSONObject().put("topic", it.first).put("content", it.second)) }
                        val rf = JSONArray()
                        hits.forEach {
                            val o = JSONObject().put("reference", it.title).put("part", it.idx + 1)
                            if (it.heading.isNotBlank()) o.put("section", it.heading)
                            if (it.page > 0) o.put("page", it.page)
                            rf.put(o.put("excerpt", it.text.take(1500)))
                        }
                        legacy.forEach { rf.put(JSONObject().put("reference", it.first).put("excerpt", it.second)) }
                        res.put("found", true).put("items", arr).put("references", rf)
                        if (hits.isNotEmpty()) res.put("hint", "إن بدا المقطع ناقصاً أو قُطع في وسطه فاقرأ الجزء السابق أو التالي بالأداة read_reference (المرجع + رقم الجزء).")
                    }
                }
                "read_reference" -> {
                    val src = s.findSource(pid, args.optString("source"))
                    if (src == null) {
                        unknown(res, s, c, pid, args.optString("source"))
                    } else {
                        val total = src.intOr("chunk_count", 0)
                        val part = args.optInt("part", 0)
                        res.put("found", true).put("reference", src.strOr("title")).put("type", SrcType.label(SrcType.of(src))).put("total_parts", total)
                        if (part <= 0 || total == 0) {
                            res.put("outline", src.strOr("outline").take(3500))
                            res.put("note", "هذا فهرس المرجع. اقرأ جزءاً محدداً بتمرير رقمه في part.")
                        } else {
                            val from = (part - 1).coerceIn(0, total - 1)
                            val arr = JSONArray()
                            for (ck in s.chunkRange(src.strOr("id"), from, minOf(total - 1, from + 1))) {
                                val o = JSONObject().put("part", ck.idx + 1)
                                if (ck.heading.isNotBlank()) o.put("section", ck.heading)
                                if (ck.page > 0) o.put("page", ck.page)
                                arr.put(o.put("text", ck.text))
                            }
                            res.put("parts", arr)
                            if (from + 2 < total) res.put("next_part", from + 3)
                        }
                    }
                }
                "search_library" -> {
                    val libs = s.libraries(pid)
                    val out = JSONArray()
                    if (libs != null) {
                        for (i in 0 until libs.length()) {
                            val l = libs.getJSONObject(i)
                            val id = l.strOr("id")
                            val key = libKeys.getOrPut(id) { s.getSecret("lib_$id") ?: "" }
                            if (key.isBlank()) continue
                            val cfg = try { JSONObject(l.optString("config", "{}")) } catch (e: Exception) { l.optJSONObject("config") ?: JSONObject() }
                            for (t in Sources.librarySearch(l.strOr("url"), key, cfg, args.optString("query"))) {
                                out.add3(t)
                            }
                        }
                    }
                    if (out.length() == 0) unknown(res, s, c, pid, args.optString("query"))
                    else res.put("found", true).put("results", out)
                }
                "remember_person" -> {
                    if (c.storeMemory) res.put("saved", s.addNote(pid, host, args.optString("person"), args.optString("note")))
                    else res.put("saved", false).put("note", "التخزين الدائم معطّل؛ احتفظ بالمعلومة في ذاكرتك خلال هذه الجلسة فقط")
                }
                "recall_person" -> {
                    val arr = JSONArray()
                    if (c.storeMemory) s.notes(pid, host, args.optString("person"), 15).forEach { arr.put(it.second) }
                    res.put("notes", arr)
                }
                "report_unknown" -> {
                    if (c.storeMemory) s.addUnanswered(pid, args.optString("question"))
                    res.put("saved", c.storeMemory)
                }
                "look_at_screen" -> res.put("shown", false).put("note", "لا توجد شاشة متاحة في هذا الوضع")
                else -> res.put("error", "unknown tool")
            }
        } catch (e: Exception) {
            res.put("error", e.message ?: "error")
        }
        return res
    }
}
