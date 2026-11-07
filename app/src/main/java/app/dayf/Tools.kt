package app.dayf

import org.json.JSONArray
import org.json.JSONObject

/** Tools the model may call. The database is the only source of facts. */
object Tools {
    fun exec(s: Supa?, c: Cfg?, host: String, name: String, args: JSONObject): JSONObject {
        val res = JSONObject()
        try {
            if (s == null || c == null) {
                res.put("error", "no database")
            } else when (name) {
                "lookup_knowledge" -> {
                    val rows = s.search(args.optString("query"))
                    if (rows.isEmpty()) {
                        res.put("found", false).put("message", c.unknownReply)
                    } else {
                        val arr = JSONArray()
                        rows.forEach { arr.put(JSONObject().put("topic", it.first).put("content", it.second)) }
                        res.put("found", true).put("items", arr)
                    }
                }
                "remember_person" -> res.put("saved", s.addNote(host, args.optString("person"), args.optString("note")))
                "recall_person" -> {
                    val arr = JSONArray()
                    s.notes(host, args.optString("person"), 15).forEach { arr.put(it.second) }
                    res.put("notes", arr)
                }
                "report_unknown" -> {
                    s.addUnanswered(host, args.optString("question"))
                    res.put("saved", true)
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
