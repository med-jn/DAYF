package app.dayf

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Gemini calls used to read sources. Every call retries by itself and falls back to another model. */
object Gem {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val json = "application/json".toMediaType()

    class Out(val text: String, val finish: String, val code: Int, val err: String) {
        val ok get() = code in 200..299
    }

    private val uploadClient = Net.client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    fun textPart(t: String): JSONObject = JSONObject().put("text", t)

    fun inlinePart(bytes: ByteArray, mime: String): JSONObject =
        JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))

    fun filePart(uri: String, mime: String? = null): JSONObject {
        val f = JSONObject().put("file_uri", uri)
        if (mime != null) f.put("mime_type", mime)
        return JSONObject().put("file_data", f)
    }

    /**
     * Retries timeouts, 429 and 5xx with growing pauses; after two failures moves to the next model in `models`.
     * `tools` is used for url_context. Thinking is switched off (faster, cheaper) and silently re-enabled if a model insists.
     */
    fun generate(key: String, models: List<String>, parts: List<JSONObject>, maxOut: Int = 30000, tools: JSONArray? = null, tries: Int = 0, asJson: Boolean = false): Out {
        var last = Out("", "", 0, "")
        val n = models.size.coerceAtLeast(1)
        // two sweeps over the models: a busy or out-of-quota model is skipped at once (each model has its own quota and load)
        val total = if (tries > 0) tries else (n * 2).coerceAtLeast(4)
        val deadline = System.currentTimeMillis() + 6 * 60_000L
        var think = false
        var attempt = 0
        while (attempt < total && System.currentTimeMillis() < deadline) {
            val model = models[attempt % n]
            attempt++
            try {
                val ps = JSONArray()
                parts.forEach { ps.put(it) }
                val cfg = JSONObject().put("temperature", 0.1).put("maxOutputTokens", maxOut)
                if (!think) cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                if (asJson) cfg.put("responseMimeType", "application/json")
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", ps))).put("generationConfig", cfg)
                if (tools != null) body.put("tools", tools)
                val req = Request.Builder().url("$BASE/models/$model:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody(json)).build()
                Net.slowClient.newCall(req).execute().use { r ->
                    val t = r.body?.string() ?: ""
                    if (r.isSuccessful) {
                        val j = JSONObject(t)
                        val cand = j.optJSONArray("candidates")?.optJSONObject(0)
                        val ps2 = cand?.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (ps2 != null) for (i in 0 until ps2.length()) sb.append(ps2.getJSONObject(i).optString("text"))
                        val block = j.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
                        last = if (block.isNotBlank() && sb.isEmpty()) Out("", "", 400, "blocked $block")
                        else Out(sb.toString(), cand?.optString("finishReason") ?: "", 200, "")
                    } else {
                        val msg = try { JSONObject(t).getJSONObject("error").optString("message") } catch (e: Exception) { t }
                        last = Out("", "", r.code, msg.take(300))
                        AppLog.add("Gemini ${r.code} ($model): ${msg.take(120)}")
                    }
                }
            } catch (e: IOException) {
                last = Out("", "", 0, e.message ?: "io")
                AppLog.add("Gemini شبكة ($model): ${e.javaClass.simpleName}")
            } catch (e: org.json.JSONException) {
                last = Out("", "", 502, "bad json")
            }
            if (last.ok) return last
            val c = last.code
            val low = last.err.lowercase()
            when {
                c == 400 && low.contains("thinking") && !think -> { think = true; attempt-- } // same model, thinking allowed
                (c == 400 || c == 404) && !low.contains("api key") -> if (n == 1) return last // this model can't do it: next one at once
                c == 0 || Net.retryable(c) -> if (attempt % n == 0 && attempt < total) Net.pause(attempt / n) // pause only after a full sweep
                else -> return last
            }
        }
        return last
    }

    /** Uploads a big file with the Files API and returns its uri, or null. Waits until Gemini has processed it. */
    fun upload(key: String, file: File, mime: String, name: String): String? {
        for (attempt in 1..3) {
            try {
                val start = Request.Builder().url("https://generativelanguage.googleapis.com/upload/v1beta/files")
                    .header("x-goog-api-key", key)
                    .header("X-Goog-Upload-Protocol", "resumable")
                    .header("X-Goog-Upload-Command", "start")
                    .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
                    .header("X-Goog-Upload-Header-Content-Type", mime)
                    .post(JSONObject().put("file", JSONObject().put("display_name", name.take(80))).toString().toRequestBody(json))
                    .build()
                var upUrl: String? = null
                uploadClient.newCall(start).execute().use { r ->
                    if (r.isSuccessful) upUrl = r.header("x-goog-upload-url")
                    else if (!Net.retryable(r.code)) return null
                }
                val target = upUrl
                if (target != null) {
                    val send = Request.Builder().url(target)
                        .header("X-Goog-Upload-Offset", "0")
                        .header("X-Goog-Upload-Command", "upload, finalize")
                        .post(file.asRequestBody(mime.toMediaType())).build()
                    var info: JSONObject? = null
                    var fatal = false
                    uploadClient.newCall(send).execute().use { r ->
                        val t = r.body?.string() ?: ""
                        if (r.isSuccessful) info = JSONObject(t).optJSONObject("file")
                        else if (!Net.retryable(r.code)) fatal = true
                    }
                    if (fatal) return null
                    val f = info
                    if (f != null) {
                        val uri = f.optString("uri")
                        var state = f.optString("state")
                        val fname = f.optString("name")
                        var waited = 0
                        while (state == "PROCESSING" && waited < 90 && fname.isNotBlank()) {
                            Thread.sleep(3000)
                            waited += 3
                            val g = Net.get("$BASE/$fname", mapOf("x-goog-api-key" to key), tries = 2)
                            state = try { JSONObject(g?.text ?: "{}").optString("state") } catch (e: Exception) { "" }
                        }
                        if (state == "FAILED") return null
                        if (uri.isNotBlank()) return uri
                    }
                }
            } catch (e: IOException) {
                AppLog.add("رفع إلى Gemini: ${e.javaClass.simpleName}")
            } catch (e: org.json.JSONException) {
            }
            Net.pause(attempt)
        }
        return null
    }
}
