package app.dayf

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun JSONObject.strOr(k: String, d: String = ""): String = if (isNull(k)) d else optString(k, d)
fun JSONObject.dblOr(k: String, d: Double): Double = if (isNull(k)) d else optDouble(k, d)
fun JSONObject.intOr(k: String, d: Int): Int = if (isNull(k)) d else optInt(k, d)
fun JSONObject.boolOr(k: String, d: Boolean): Boolean = if (isNull(k)) d else optBoolean(k, d)

class Cfg(val j: JSONObject) {
    val name: String get() = j.strOr("name", "ضيف")
    val aliases: String get() = j.strOr("aliases")
    val voice: String get() = j.strOr("voice", "Charon")
    val model: String get() = j.strOr("model")
    val visionModel: String get() = j.strOr("vision_model", "gemini-2.5-flash")
    val dialect: String get() = j.strOr("dialect", "عربية بسيطة")
    val persona: String get() = j.strOr("persona")
    val extra: String get() = j.strOr("extra_instructions")
    val opening: String get() = j.strOr("opening_line")
    val unknownReply: String get() = j.strOr("unknown_reply", "ما عندي معلومة عن هذا.")
    val temperature: Double get() = j.dblOr("temperature", 0.4)
    val silenceMs: Int get() = j.intOr("silence_ms", 800)
    val addressWindowS: Int get() = j.intOr("address_window_s", 25)
    val followupWindowS: Int get() = j.intOr("followup_window_s", 20)
    val visionIntervalS: Int get() = j.intOr("vision_interval_s", 6)
    val frameIntervalMs: Int get() = j.intOr("frame_interval_ms", 2000)
    val knowledgeInlineChars: Int get() = j.intOr("knowledge_inline_chars", 6000)
    val allowUnknown: Boolean get() = j.boolOr("allow_unknown_state", false)
    val defaultMode: String get() = j.strOr("default_mode", "ON_CALL")
    val outGain: Double get() = j.dblOr("out_gain", 1.0)
    val visionPrompt: String get() = j.strOr("vision_prompt")
    val keywords: JSONObject? get() = j.optJSONObject("keywords")
    val voiceProvider: String get() = j.strOr("voice_provider", "gemini")
    val elevenVoice1: String get() = j.strOr("eleven_voice_id")
    val elevenVoice2: String get() = j.strOr("eleven_voice_2_id")
    val elevenName1: String get() = j.strOr("eleven_voice_1_name", "الصوت 1")
    val elevenName2: String get() = j.strOr("eleven_voice_2_name", "الصوت 2")
    val elevenActive: Int get() = j.intOr("eleven_active", 1)
    val elevenModel: String get() = j.strOr("eleven_model", "eleven_flash_v2_5")
    val elevenStability: Double get() = j.dblOr("eleven_stability", 0.45)
    val elevenSimilarity: Double get() = j.dblOr("eleven_similarity", 0.8)
    val greetOnJoin: Boolean get() = j.boolOr("greet_on_join", true)
    val screenMode: String get() = j.strOr("screen_mode", "on_demand")
    val interruptPolicy: String get() = j.strOr("interrupt_policy", "smart")
    val yieldChars: Int get() = j.intOr("yield_chars", 30)
    val yieldWindowMs: Int get() = j.intOr("yield_window_ms", 3000)
    val proactiveAudio: Boolean get() = j.boolOr("proactive_audio", true)
    val verifyTtlS: Int get() = j.intOr("verify_ttl_s", 60)
    val quickCommands: List<String>
        get() {
            val a = j.optJSONArray("quick_commands") ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        }
}

class Supa(baseUrl: String, private val key: String, private val admin: String = "") {
    private val base = baseUrl.trim().trimEnd('/')
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    @Volatile var lastError = ""

    private fun builder(path: String, params: List<Pair<String, String>> = emptyList()): Request.Builder {
        val ub = "$base/rest/v1/$path".toHttpUrl().newBuilder()
        for (p in params) ub.addQueryParameter(p.first, p.second)
        val rb = Request.Builder().url(ub.build()).header("apikey", key)
        if (key.startsWith("eyJ")) rb.header("Authorization", "Bearer $key")
        if (admin.isNotBlank()) rb.header("x-dayf-admin", admin)
        return rb
    }

    private fun select(table: String, params: List<Pair<String, String>>): JSONArray? {
        return try {
            http.newCall(builder(table, params).get().build()).execute().use { r ->
                val body = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    lastError = "HTTP ${r.code} ${body.take(160)}"
                    null
                } else JSONArray(body)
            }
        } catch (e: Exception) {
            lastError = e.message ?: "error"
            null
        }
    }

    fun insert(table: String, row: JSONObject): Boolean {
        return try {
            val req = builder(table).header("Prefer", "return=minimal")
                .post(row.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) lastError = "HTTP ${r.code} ${r.body?.string()?.take(160)}"
                r.isSuccessful
            }
        } catch (e: Exception) {
            lastError = e.message ?: "error"
            false
        }
    }

    private fun call(
        method: String, table: String, params: List<Pair<String, String>>, body: JSONObject?
    ): Pair<Boolean, String> {
        return try {
            val rb = builder(table, params).header("Prefer", "return=representation")
            val media = "application/json".toMediaType()
            val rq = when (method) {
                "POST" -> rb.post(body.toString().toRequestBody(media))
                "PATCH" -> rb.patch(body.toString().toRequestBody(media))
                "DELETE" -> rb.delete()
                else -> rb.get()
            }.build()
            http.newCall(rq).execute().use { r ->
                val t = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    lastError = "HTTP ${r.code} ${t.take(160)}"
                    false to lastError
                } else if (method != "POST" && t.trim() == "[]") {
                    lastError = "لم يُحفظ شيء: تحقق من رمز الإدارة وأنك شغّلت ملف migration_v3.sql"
                    false to lastError
                } else true to t
            }
        } catch (e: Exception) {
            lastError = e.message ?: "error"
            false to lastError
        }
    }

    fun updateConfig(patch: JSONObject): Pair<Boolean, String> =
        call("PATCH", "agent_config", listOf("id" to "eq.1"), patch)

    fun listRules(): JSONArray? =
        select("rules", listOf("order" to "id.asc", "select" to "id,text,priority,active"))

    fun listKnowledge(): JSONArray? =
        select("knowledge", listOf("order" to "id.asc", "select" to "id,topic,content,keywords,active"))

    fun saveRow(table: String, id: Long?, row: JSONObject): Pair<Boolean, String> =
        if (id == null) call("POST", table, emptyList(), row)
        else call("PATCH", table, listOf("id" to "eq.$id"), row)

    fun removeRow(table: String, id: Long): Pair<Boolean, String> =
        call("DELETE", table, listOf("id" to "eq.$id"), null)

    fun config(): JSONObject? =
        select("agent_config", listOf("id" to "eq.1", "select" to "*"))?.optJSONObject(0)

    fun rules(): List<String> {
        val a = select("rules", listOf("active" to "eq.true", "order" to "priority.asc,id.asc", "select" to "text"))
            ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).strOr("text") }.filter { it.isNotBlank() }
    }

    fun knowledge(): List<Pair<String, String>> {
        val a = select("knowledge", listOf("active" to "eq.true", "order" to "id.asc", "limit" to "300", "select" to "topic,content"))
            ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            o.strOr("topic") to o.strOr("content")
        }
    }

    fun search(q: String): List<Pair<String, String>> {
        val words = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct().take(4)
        if (words.isEmpty()) return emptyList()
        val variants = ArrayList<String>()
        for (w in words) {
            variants.add(w)
            if (w.startsWith("ال") && w.length > 4) variants.add(w.substring(2))
        }
        val clauses = variants.joinToString(",") { "topic.ilike.*$it*,content.ilike.*$it*,keywords.ilike.*$it*" }
        val a = select(
            "knowledge",
            listOf("active" to "eq.true", "or" to "($clauses)", "select" to "topic,content", "limit" to "25")
        ) ?: return emptyList()
        val rows = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            o.strOr("topic") to o.strOr("content")
        }
        return rows.sortedByDescending { r ->
            val t = (r.first + " " + r.second).lowercase()
            variants.count { t.contains(it.lowercase()) }
        }.take(5)
    }

    fun notes(host: String, person: String? = null, limit: Int = 40): List<Pair<String, String>> {
        val params = ArrayList<Pair<String, String>>()
        params.add("host" to "eq.$host")
        if (person != null) {
            val p = person.replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
            if (p.isNotEmpty()) params.add("person" to "ilike.*$p*")
        }
        params.add("order" to "created_at.desc")
        params.add("limit" to limit.toString())
        params.add("select" to "person,note")
        val a = select("person_notes", params) ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            o.strOr("person") to o.strOr("note")
        }
    }

    fun recentTranscripts(host: String, n: Int): List<String> {
        val a = select(
            "transcripts",
            listOf("host" to "eq.$host", "order" to "created_at.desc", "limit" to n.toString(), "select" to "role,text")
        ) ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            (if (o.strOr("role") == "agent") "أنت: " else "الحاضرون: ") + o.strOr("text")
        }.reversed()
    }

    fun addTranscript(liveId: String, host: String, role: String, text: String) {
        insert("transcripts", JSONObject().put("live_id", liveId).put("host", host).put("role", role).put("text", text))
    }

    fun addNote(host: String, person: String, note: String): Boolean =
        insert("person_notes", JSONObject().put("host", host).put("person", person).put("note", note))

    fun addUnanswered(host: String, q: String) {
        insert("unanswered", JSONObject().put("host", host).put("question", q))
    }

    fun startSession(liveId: String, host: String) {
        insert("sessions", JSONObject().put("id", liveId).put("host", host))
    }
}
