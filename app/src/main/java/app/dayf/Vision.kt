package app.dayf

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Vision {
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    const val DEFAULT_PROMPT =
        "This is a phone screenshot. Decide whether it shows the TikTok app inside a LIVE broadcast and what role the account named \"{me}\" has in it. " +
            "Roles: viewer = just watching, no tile of {me}; requested = a request to join as guest was sent and is waiting for the host; " +
            "invited = an invitation to join is shown to {me}; guest = {me} appears as a participant next to the host (second video tile or audio tile); " +
            "not_live = not inside a live; unknown = cannot tell. " +
            "my_mic: open if {me}'s tile shows an active microphone, muted if its mic icon is crossed or off, unknown otherwise. " +
            "Answer ONLY with JSON: {\"role\":\"viewer|requested|invited|guest|not_live|unknown\",\"my_mic\":\"open|muted|unknown\",\"evidence\":\"max 12 words\"}"

    fun classify(apiKey: String, model: String, prompt: String, jpeg: ByteArray): JSONObject? {
        return try {
            val parts = JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    )
                )
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val txt = JSONObject(r.body?.string() ?: return null)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                JSONObject(txt.replace("```json", "").replace("```", "").trim())
            }
        } catch (e: Exception) {
            null
        }
    }
}
