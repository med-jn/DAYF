package app.dayf

import android.content.Context
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

class SourceInfo(val id: String, val kind: String, val type: String, val title: String, val brief: String, val parts: Int)

class ChunkHit(val sourceId: String, val title: String, val idx: Int, val heading: String, val page: Int, val text: String)

class Cfg(val j: JSONObject) {
    val title: String get() = j.strOr("title", "ضيفي")
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
    val silenceMs: Int get() = j.intOr("silence_ms", 700)
    val addressWindowS: Int get() = j.intOr("address_window_s", 25)
    val followupWindowS: Int get() = j.intOr("followup_window_s", 20)
    val visionIntervalS: Int get() = j.intOr("vision_interval_s", 6)
    val frameIntervalMs: Int get() = j.intOr("frame_interval_ms", 2000)
    val knowledgeInlineChars: Int get() = j.intOr("knowledge_inline_chars", 6000)
    val allowUnknown: Boolean get() = j.boolOr("allow_unknown_state", false)
    val defaultMode: String get() = j.strOr("default_mode", "ON_CALL")
    val outGain: Double get() = j.dblOr("out_gain", 1.0)
    val visionPrompt: String get() = j.strOr("vision_prompt")
    val storeMemory: Boolean get() = j.boolOr("store_memory", false)
    val gender: String get() = j.strOr("gender", "male")
    val responseSpeed: String get() = j.strOr("response_speed", "balanced")
    val fastMode: Boolean get() = j.boolOr("fast_mode", true)
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
    val speechStyle: String get() = j.strOr("speech_style", "دافئ وقريب")
    val affectiveDialog: Boolean get() = j.boolOr("affective_dialog", true)
    val interruptPolicy: String get() = j.strOr("interrupt_policy", "smart")
    val yieldChars: Int get() = j.intOr("yield_chars", 30)
    val yieldWindowMs: Int get() = j.intOr("yield_window_ms", 3000)
    val proactiveAudio: Boolean get() = j.boolOr("proactive_audio", true)
    val verifyTtlS: Int get() = j.intOr("verify_ttl_s", 180)
    val quickCommands: List<String>
        get() {
            val a = j.optJSONArray("quick_commands") ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        }
}

/** REST client for the signed-in user. Every call carries the user's JWT, so Row Level Security isolates tenants. */
class Supa(private val ctx: Context) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val media = "application/json".toMediaType()
    @Volatile var lastError = ""

    private fun builder(path: String, params: List<Pair<String, String>> = emptyList()): Request.Builder? {
        val token = Auth.token(ctx)
        if (token == null) {
            lastError = "لست مسجلاً للدخول"
            return null
        }
        val ub = "${Config.SUPABASE_URL}/rest/v1/$path".toHttpUrl().newBuilder()
        for (p in params) ub.addQueryParameter(p.first, p.second)
        return Request.Builder().url(ub.build())
            .header("apikey", Config.SUPABASE_KEY)
            .header("Authorization", "Bearer $token")
    }

    /** Logs the technical detail for the developer, returns plain Arabic for the user. */
    /** The raw error body of the last failed request (never shown to the user; used to tell "invalid code" from "busy"). */
    @Volatile var lastRaw = ""

    private fun describe(code: Int, body: String): String {
        val raw = try {
            val j = JSONObject(body)
            "HTTP $code ${j.optString("code")} ${j.optString("message").take(200)}".trim()
        } catch (e: Exception) {
            "HTTP $code ${body.take(200)}"
        }
        lastRaw = body
        AppLog.add("قاعدة البيانات: $raw")
        return Net.friendlyHttp(code, body)
    }

    private class Try(val ok: Boolean, val text: String, val retry: Boolean)

    private fun once(
        method: String, path: String, params: List<Pair<String, String>>, body: Any?, rep: Boolean
    ): Try {
        return try {
            val rb = builder(path, params) ?: return Try(false, lastError, false)
            if (rep) rb.header("Prefer", "return=representation")
            val rq = when (method) {
                "POST" -> rb.post((body ?: JSONObject()).toString().toRequestBody(media))
                "PATCH" -> rb.patch((body ?: JSONObject()).toString().toRequestBody(media))
                "DELETE" -> rb.delete()
                else -> rb.get()
            }.build()
            http.newCall(rq).execute().use { r ->
                val t = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    lastError = describe(r.code, t)
                    Try(false, lastError, r.code == 429 || r.code == 502 || r.code == 503 || r.code == 504)
                } else if (rep && (method == "PATCH" || method == "DELETE") && t.trim() == "[]") {
                    lastError = "لم يُنفَّذ: العنصر غير موجود أو غير مسموح"
                    Try(false, lastError, false)
                } else Try(true, t, false)
            }
        } catch (e: Exception) {
            AppLog.add("قاعدة البيانات: ${e.javaClass.simpleName} ${e.message}")
            lastError = Net.friendly(e)
            // a POST that timed out may already have been applied: only retry it when the request never left the phone
            val neverSent = e is java.net.UnknownHostException || e is java.net.ConnectException
            Try(false, lastError, method != "POST" || neverSent)
        }
    }

    /** Silent retries (3 attempts) for network drops and busy servers; the user only sees a message if all of them fail. */
    private fun exec(
        method: String, path: String, params: List<Pair<String, String>> = emptyList(),
        body: Any? = null, rep: Boolean = false
    ): Pair<Boolean, String> {
        var attempt = 0
        while (true) {
            attempt++
            val r = once(method, path, params, body, rep)
            if (r.ok || !r.retry || attempt >= 3) return r.ok to r.text
            Net.pause(attempt)
        }
    }

    private fun select(table: String, params: List<Pair<String, String>>): JSONArray? {
        val r = exec("GET", table, params)
        return if (r.first) try { JSONArray(r.second) } catch (e: Exception) { null } else null
    }

    // ---------------------------------------------------------------- profile & personas
    /** Creates the profile and a first persona if missing; returns the active persona id. */
    fun ensureProfile(): String? {
        val r = exec("POST", "rpc/ensure_profile")
        return if (r.first) r.second.trim().trim('"').ifBlank { null } else null
    }

    fun personas(): JSONArray? =
        select("personas", listOf("select" to "id,title,name,created_at,user_id", "order" to "created_at.asc"))

    fun persona(id: String): JSONObject? =
        select("personas", listOf("id" to "eq.$id", "select" to "*"))?.optJSONObject(0)

    fun updatePersona(id: String, patch: JSONObject): Pair<Boolean, String> =
        exec("PATCH", "personas", listOf("id" to "eq.$id"), patch, true)

    fun createPersona(row: JSONObject): String? {
        val r = exec("POST", "personas", emptyList(), row, true)
        if (!r.first) return null
        return try { JSONArray(r.second).getJSONObject(0).getString("id") } catch (e: Exception) { null }
    }

    fun deletePersona(id: String): Pair<Boolean, String> =
        exec("DELETE", "personas", listOf("id" to "eq.$id"), null, true)

    fun setActive(id: String): Pair<Boolean, String> =
        exec("PATCH", "profiles", listOf("user_id" to "eq.${Auth.userId(ctx)}"), JSONObject().put("active_persona_id", id), true)

    // ---------------------------------------------------------------- rules & knowledge (per persona)
    fun rules(pid: String): List<String> {
        val a = select("rules", listOf("persona_id" to "eq.$pid", "active" to "eq.true", "order" to "priority.asc,id.asc", "select" to "text"))
            ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).strOr("text") }.filter { it.isNotBlank() }
    }

    fun knowledge(pid: String): List<Pair<String, String>> {
        val a = select("knowledge", listOf("persona_id" to "eq.$pid", "active" to "eq.true", "order" to "id.asc", "limit" to "300", "select" to "topic,content"))
            ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            o.strOr("topic") to o.strOr("content")
        }
    }

    fun search(pid: String, q: String): List<Pair<String, String>> {
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
            listOf("persona_id" to "eq.$pid", "active" to "eq.true", "or" to "($clauses)", "select" to "topic,content", "limit" to "25")
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

    fun listRules(pid: String): JSONArray? =
        select("rules", listOf("persona_id" to "eq.$pid", "order" to "id.asc", "select" to "id,text,priority,active"))

    fun listKnowledge(pid: String): JSONArray? =
        select("knowledge", listOf("persona_id" to "eq.$pid", "order" to "id.asc", "select" to "id,topic,content,keywords,active"))

    fun saveRow(table: String, id: Long?, row: JSONObject): Pair<Boolean, String> =
        if (id == null) exec("POST", table, emptyList(), row, true)
        else exec("PATCH", table, listOf("id" to "eq.$id"), row, true)

    fun removeRow(table: String, id: Long): Pair<Boolean, String> =
        exec("DELETE", table, listOf("id" to "eq.$id"), null, true)

    // ---------------------------------------------------------------- opt-in memory and reports
    fun notes(pid: String, host: String, person: String? = null, limit: Int = 40): List<Pair<String, String>> {
        val params = ArrayList<Pair<String, String>>()
        params.add("persona_id" to "eq.$pid")
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

    fun addNote(pid: String, host: String, person: String, note: String): Boolean =
        exec("POST", "person_notes", emptyList(), JSONObject().put("persona_id", pid).put("host", host).put("person", person).put("note", note)).first

    fun addUnanswered(pid: String, q: String) {
        exec("POST", "unanswered", emptyList(), JSONObject().put("persona_id", pid).put("question", q))
    }

    /** In-app report of an inappropriate agent reply (Google Play AI-generated content policy). Stores only the agent's own words. */
    fun addReport(pid: String, agentText: String, reason: String): Boolean =
        exec("POST", "reports", emptyList(), JSONObject().put("persona_id", pid).put("agent_text", agentText.take(1500)).put("reason", reason)).first

    // ---------------------------------------------------------------- encrypted secrets (server-side encryption)
    fun saveSecret(name: String, value: String): Boolean =
        exec("POST", "rpc/save_secret", emptyList(), JSONObject().put("p_name", name).put("p_value", value)).first

    fun getSecret(name: String): String? {
        val r = exec("POST", "rpc/get_secret", emptyList(), JSONObject().put("p_name", name))
        if (!r.first) return null
        return try {
            val v = org.json.JSONTokener(r.second).nextValue()
            if (v is String) v else null
        } catch (e: Exception) {
            null
        }
    }

    fun deleteSecret(name: String): Boolean =
        exec("POST", "rpc/delete_secret", emptyList(), JSONObject().put("p_name", name)).first

    fun deleteAccount(): Pair<Boolean, String> = exec("POST", "rpc/delete_my_account")

    // ---------------------------------------------------------------- sources (references)
    fun listSources(pid: String): JSONArray? =
        select("sources", listOf("persona_id" to "eq.$pid", "order" to "updated_at.desc", "select" to "id,kind,type,title,url,config,status,error,brief,progress,attempts,next_retry_at,chunk_count,char_count,page_count,file_name,via,note,updated_at,folder_id,folder_manual,drive_file_id,media_secs"))

    fun sourceInfos(pid: String): List<SourceInfo> {
        val a = select("sources", listOf("persona_id" to "eq.$pid", "status" to "eq.ready", "select" to "id,kind,type,title,brief,chunk_count")) ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            SourceInfo(o.strOr("id"), o.strOr("kind"), SrcType.of(o), o.strOr("title"), o.strOr("brief"), o.intOr("chunk_count", 0))
        }
    }

    /** Excerpts from the digests of web/youtube sources that mention the query. */
    fun searchSources(pid: String, q: String): List<Pair<String, String>> {
        val ws = Sources.words(q)
        if (ws.isEmpty()) return emptyList()
        val clauses = ws.joinToString(",") { "digest.ilike.*$it*,title.ilike.*$it*" }
        val a = select(
            "sources",
            listOf("persona_id" to "eq.$pid", "status" to "eq.ready", "kind" to "in.(web,site,youtube,file)", "or" to "($clauses)", "select" to "title,digest", "limit" to "4")
        ) ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            o.strOr("title") to Sources.excerpt(o.strOr("digest"), ws, 900)
        }
    }

    /** Libraries connected to the persona: id, url and mapping. */
    fun libraries(pid: String): JSONArray? =
        select("sources", listOf("persona_id" to "eq.$pid", "kind" to "eq.supabase", "select" to "id,title,url,config"))

    fun createSource(row: JSONObject): String? {
        val r = exec("POST", "sources", emptyList(), row, true)
        if (!r.first) return null
        return try { JSONArray(r.second).getJSONObject(0).getString("id") } catch (e: Exception) { null }
    }

    fun updateSource(id: String, patch: JSONObject): Pair<Boolean, String> =
        exec("PATCH", "sources", listOf("id" to "eq.$id"), patch, true)

    fun deleteSource(id: String): Pair<Boolean, String> =
        exec("DELETE", "sources", listOf("id" to "eq.$id"), null, true)

    // ---------------------------------------------------------------- source content (Markdown chunks)
    fun getSource(id: String): JSONObject? =
        select("sources", listOf("id" to "eq.$id", "select" to "id,persona_id,kind,type,title,url,config,status,attempts,file_name,mime,content_hash,chunk_count,folder_id,drive_file_id"))?.optJSONObject(0)

    fun getOutline(id: String): JSONObject? =
        select("sources", listOf("id" to "eq.$id", "select" to "id,title,type,kind,outline,brief,chunk_count,char_count,page_count,note,via,url,drive_file_id,media_mime,media_secs"))?.optJSONObject(0)

    fun deleteChunks(sourceId: String): Boolean = exec("DELETE", "source_chunks", listOf("source_id" to "eq.$sourceId")).first

    fun insertChunks(pid: String, sid: String, chunks: List<Chunk>): Boolean {
        val arr = JSONArray()
        for (c in chunks) {
            arr.put(JSONObject().put("persona_id", pid).put("source_id", sid).put("idx", c.idx).put("heading", c.heading).put("page", c.page).put("text", c.text))
        }
        return exec("POST", "source_chunks", emptyList(), arr).first
    }

    /** The most relevant parts of all ready sources for these words (ranked in the database). */
    fun searchChunks(pid: String, words: List<String>, limit: Int = 4): List<ChunkHit> {
        if (words.isEmpty()) return emptyList()
        val wa = JSONArray()
        words.forEach { wa.put(it) }
        val r = exec("POST", "rpc/search_source_chunks", emptyList(), JSONObject().put("p_persona", pid).put("p_words", wa).put("p_limit", limit))
        if (!r.first) return emptyList()
        val a = try { JSONArray(r.second) } catch (e: Exception) { return emptyList() }
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ChunkHit(o.strOr("source_id"), o.strOr("title"), o.intOr("idx", 0), o.strOr("heading"), o.intOr("page", 0), o.strOr("text"))
        }
    }

    /** Finds a ready source by id or by (part of) its title. */
    fun findSource(pid: String, name: String, onlyType: String? = null): JSONObject? {
        val n = name.trim()
        if (n.isEmpty() && onlyType == null) return null
        if (Regex("^[0-9a-fA-F-]{36}$").matches(n)) return getOutline(n)
        val ws = Sources.words(n).ifEmpty { if (n.isEmpty()) emptyList() else listOf(n) }
        if (ws.isEmpty()) {
            val a = select("sources", listOf("persona_id" to "eq.$pid", "status" to "eq.ready", "type" to "eq.$onlyType", "select" to "id", "limit" to "1"))
            return a?.optJSONObject(0)?.let { getOutline(it.strOr("id")) }
        }
        val clauses = ws.joinToString(",") { "title.ilike.*$it*" }
        val a = select(
            "sources",
            listOfNotNull("persona_id" to "eq.$pid", "status" to "eq.ready", "or" to "($clauses)", "select" to "id,title", "limit" to "10", onlyType?.let { "type" to "eq.$it" })
        ) ?: return null
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val t = o.strOr("title").lowercase()
            val sc = ws.count { t.contains(it.lowercase()) }
            if (sc > bestScore) { bestScore = sc; best = o }
        }
        return best?.let { getOutline(it.strOr("id")) }
    }

    fun chunkRange(sid: String, from: Int, to: Int): List<Chunk> {
        val a = select(
            "source_chunks",
            listOf("source_id" to "eq.$sid", "idx" to "gte.$from", "idx" to "lte.$to", "order" to "idx.asc", "select" to "idx,heading,page,text")
        ) ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Chunk(o.intOr("idx", 0), o.strOr("heading"), o.strOr("text"), o.intOr("page", 0))
        }
    }

    // ---------------------------------------------------------------- folders
    fun listFolders(pid: String): JSONArray? =
        select("source_folders", listOf("persona_id" to "eq.$pid", "order" to "name.asc", "select" to "id,name,auto"))

    /** Finds a folder by name or creates it. */
    fun ensureFolder(pid: String, name: String, auto: Boolean): String? {
        fun find(): String? =
            select("source_folders", listOf("persona_id" to "eq.$pid", "name" to "eq.$name", "select" to "id", "limit" to "1"))?.optJSONObject(0)?.strOr("id")
        find()?.let { return it }
        val r = exec("POST", "source_folders", emptyList(), JSONObject().put("persona_id", pid).put("name", name).put("auto", auto), true)
        if (r.first) {
            try { return JSONArray(r.second).getJSONObject(0).strOr("id") } catch (e: Exception) { }
        }
        return find() // lost a race with another thread that created it first
    }

    fun renameFolder(id: String, name: String): Boolean =
        exec("PATCH", "source_folders", listOf("id" to "eq.$id"), JSONObject().put("name", name).put("auto", false), true).first

    fun deleteFolder(id: String): Boolean = exec("DELETE", "source_folders", listOf("id" to "eq.$id")).first

    /** Moves sources into a folder (null = unfiled). manual=true protects the choice from the smart organizer. */
    fun moveSources(ids: List<String>, folderId: String?, manual: Boolean): Boolean {
        var ok = true
        for (chunk in ids.chunked(60)) {
            val body = JSONObject().put("folder_id", folderId ?: JSONObject.NULL).put("folder_manual", manual)
            if (!exec("PATCH", "sources", listOf("id" to "in.(${chunk.joinToString(",")})"), body, true).first) ok = false
        }
        return ok
    }

    fun audioTitles(pid: String): String {
        val a = select("sources", listOf("persona_id" to "eq.$pid", "status" to "eq.ready", "type" to "eq.audio", "select" to "title", "limit" to "20")) ?: return ""
        return (0 until a.length()).joinToString("، ") { a.getJSONObject(it).strOr("title") }
    }

    fun audioSources(pid: String): JSONArray =
        select("sources", listOf("persona_id" to "eq.$pid", "status" to "eq.ready", "type" to "eq.audio", "select" to "id,title,drive_file_id,media_secs,outline,chunk_count", "limit" to "30")) ?: JSONArray()

    // ---------------------------------------------------------------- sharing
    fun appConfig(key: String): String =
        select("app_config", listOf("key" to "eq.$key", "select" to "value", "limit" to "1"))?.optJSONObject(0)?.strOr("value") ?: ""

    fun createShare(pid: String, mode: String, label: String): String? {
        val r = exec("POST", "rpc/create_share", emptyList(), JSONObject().put("p_persona", pid).put("p_mode", mode).put("p_label", label))
        return if (r.first) r.second.trim().trim('"').ifBlank { null } else null
    }

    fun listShares(pid: String): JSONArray =
        select("persona_shares", listOf("persona_id" to "eq.$pid", "revoked" to "eq.false", "order" to "created_at.desc", "select" to "id,mode,code,label,uses,created_at")) ?: JSONArray()

    fun revokeShare(id: String): Boolean = exec("POST", "rpc/revoke_share", emptyList(), JSONObject().put("p_share", id)).first

    fun hasShares(pid: String): Boolean =
        (select("persona_shares", listOf("persona_id" to "eq.$pid", "revoked" to "eq.false", "select" to "id", "limit" to "1")) ?: JSONArray()).length() > 0

    fun members(pid: String): JSONArray =
        select("persona_members", listOf("persona_id" to "eq.$pid", "order" to "created_at.asc", "select" to "user_id,display,role,created_at")) ?: JSONArray()

    fun myRole(pid: String): String {
        val r = exec("POST", "rpc/my_role", emptyList(), JSONObject().put("p", pid))
        return if (r.first) r.second.trim().trim('"') else ""
    }

    fun isShared(pid: String): Boolean {
        val r = exec("POST", "rpc/is_shared", emptyList(), JSONObject().put("p", pid))
        return r.first && r.second.trim() == "true"
    }

    fun setShareMode(id: String, mode: String): Boolean =
        exec("POST", "rpc/set_share_mode", emptyList(), JSONObject().put("p_share", id).put("p_mode", mode)).first

    fun removeMember(pid: String, userId: String): Boolean =
        exec("DELETE", "persona_members", listOf("persona_id" to "eq.$pid", "user_id" to "eq.$userId")).first

    fun clonePersona(pid: String): JSONObject? {
        val r = exec("POST", "rpc/clone_persona", emptyList(), JSONObject().put("p_persona", pid))
        if (!r.first) return null
        return try { JSONObject(r.second) } catch (e: Exception) { null }
    }

    fun leavePersona(pid: String): Boolean =
        exec("DELETE", "persona_members", listOf("persona_id" to "eq.$pid", "user_id" to "eq.${Auth.userId(ctx)}")).first

    fun previewShare(code: String): JSONObject? {
        val r = exec("POST", "rpc/preview_share", emptyList(), JSONObject().put("p_code", code.trim()))
        if (!r.first) return null
        return try { JSONArray(r.second).optJSONObject(0) } catch (e: Exception) { null }
    }

    fun acceptShare(code: String): JSONObject? {
        val r = exec("POST", "rpc/accept_share", emptyList(), JSONObject().put("p_code", code.trim()))
        if (!r.first) return null
        return try { JSONObject(r.second) } catch (e: Exception) { null }
    }

    fun cloneChunks(srcPersona: String, pairs: JSONArray): Boolean =
        exec("POST", "rpc/clone_chunks", emptyList(), JSONObject().put("p_src", srcPersona).put("p_pairs", pairs)).first
}
