package app.dayf

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Helpers shared by the search code, and the live connection to external Supabase libraries (searched live, never copied).
 * Reading links, files and videos lives in Ingest.
 */
object Sources {
    private val STRIP = Regex("[\\u064B-\\u065F\\u0670\\u0640]") // tashkeel and tatweel

    /** Search words from a question: letters only, no diacritics, with common Arabic prefixes removed as extra variants. */
    fun words(q: String): List<String> {
        val base = STRIP.replace(q, "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct().take(6)
        val out = LinkedHashSet<String>()
        for (w in base) {
            out.add(w)
            for (pre in listOf("وال", "بال", "كال", "فال", "لل", "ال")) {
                if (w.startsWith(pre) && w.length > pre.length + 2) { out.add(w.substring(pre.length)); break }
            }
            if (w.startsWith("و") && w.length > 4) out.add(w.substring(1))
        }
        return out.toList().take(10)
    }

    /** A window of text around the first match of any word. */
    fun excerpt(text: String, words: List<String>, width: Int): String {
        if (text.length <= width) return text
        val low = text.lowercase()
        var at = -1
        for (w in words) {
            val i = low.indexOf(w.lowercase())
            if (i >= 0 && (at < 0 || i < at)) at = i
        }
        if (at < 0) return text.take(width)
        val from = (at - width / 3).coerceAtLeast(0)
        return text.substring(from, minOf(text.length, from + width))
    }

    fun brief(digest: String): String = digest.replace(Regex("[#*\\n\\r]+"), " ").replace(Regex("\\s+"), " ").trim().take(180)

    // ---------------------------------------------------------------- external Supabase library
    private fun ext(url: String, key: String, path: String, params: List<Pair<String, String>>, count: Boolean = false): Triple<String?, String?, String> {
        var err = ""
        for (attempt in 1..3) {
            try {
                val ub = "${url.trim().trimEnd('/')}/rest/v1/$path".toHttpUrl().newBuilder()
                for (p in params) ub.addQueryParameter(p.first, p.second)
                val rb = Request.Builder().url(ub.build()).header("apikey", key)
                if (key.startsWith("eyJ")) rb.header("Authorization", "Bearer $key")
                if (count) rb.header("Prefer", "count=exact")
                var retry = false
                var result: Triple<String?, String?, String>? = null
                Net.client.newCall(rb.build()).execute().use { r ->
                    val t = r.body?.string() ?: ""
                    if (r.isSuccessful) result = Triple(t, r.header("Content-Range"), "")
                    else {
                        AppLog.add("مكتبة خارجية: HTTP ${r.code} ${t.take(120)}")
                        err = Net.friendlyHttp(r.code, t)
                        retry = Net.retryable(r.code)
                    }
                }
                result?.let { return it }
                if (!retry) break
            } catch (e: IllegalArgumentException) {
                return Triple(null, null, "رابط المكتبة غير صالح")
            } catch (e: Exception) {
                err = Net.friendly(e)
            }
            if (attempt < 3) Net.pause(attempt)
        }
        return Triple(null, null, err.ifBlank { "تعذّر الوصول إلى المكتبة" })
    }

    /** Catalog of the library: how many items and sample titles, so the agent knows what is inside. Returns (digest, error). */
    fun libraryCatalog(url: String, key: String, cfg: JSONObject): Pair<String?, String> {
        val table = cfg.optString("table")
        val titleCol = cfg.optString("titleCol")
        if (table.isBlank() || titleCol.isBlank()) return null to "حدّد اسم الجدول وعمود العنوان"
        val r = ext(url, key, table, listOf("select" to titleCol, "limit" to "400"), true)
        val body = r.first ?: return null to r.third
        val total = r.second?.substringAfter('/')?.toIntOrNull()
        val arr = try { JSONArray(body) } catch (e: Exception) { return null to "رد غير متوقع من المكتبة" }
        val titles = (0 until arr.length()).map { arr.getJSONObject(it).optString(titleCol) }.filter { it.isNotBlank() }
        val sb = StringBuilder("مكتبة رقمية فيها ${total ?: titles.size} عنصراً. من محتوياتها: ")
        for (t in titles) {
            if (sb.length > 6000) break
            sb.append(t).append(" | ")
        }
        return sb.toString() to ""
    }

    /** Live search inside the library. Returns (title, snippet, link). */
    fun librarySearch(url: String, key: String, cfg: JSONObject, q: String): List<Triple<String, String, String>> {
        val table = cfg.optString("table")
        val titleCol = cfg.optString("titleCol")
        if (table.isBlank() || titleCol.isBlank()) return emptyList()
        val textCol = cfg.optString("textCol")
        val urlCol = cfg.optString("urlCol")
        val searchCols = cfg.optString("searchCols").split(',', '،').map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOfNotNull(titleCol, textCol.ifBlank { null }) }
        val ws = words(q)
        if (ws.isEmpty()) return emptyList()
        val clauses = ws.flatMap { w -> searchCols.map { "$it.ilike.*$w*" } }.joinToString(",")
        val cols = listOf(titleCol, textCol, urlCol).filter { it.isNotBlank() }.distinct().joinToString(",")
        val r = ext(url, key, table, listOf("select" to cols, "or" to "($clauses)", "limit" to "5"))
        val body = r.first ?: return emptyList()
        val arr = try { JSONArray(body) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Triple(
                o.optString(titleCol),
                if (textCol.isBlank()) "" else excerpt(o.optString(textCol), ws, 700),
                if (urlCol.isBlank()) "" else o.optString(urlCol)
            )
        }
    }
}
