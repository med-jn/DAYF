package app.dayf

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LiveClient(private val apiKey: String, private val l: Listener) {

    interface Listener {
        fun onSetupComplete()
        fun onAudio(pcm24k: ByteArray)
        fun onInputText(t: String)
        fun onOutputText(t: String)
        fun onTurnComplete()
        fun onInterrupted()
        fun onToolCall(id: String, name: String, args: JSONObject)
        fun onResumeHandle(h: String)
        fun onGoAway()
        fun onClosed(code: Int, reason: String)
    }

    companion object {
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private val http = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        private val rest = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

        private fun score(n: String): Double {
            val m = Regex("(\\d+)(?:\\.(\\d+))?").find(n)
            val v = if (m != null) (m.groupValues[1] + "." + m.groupValues[2].ifEmpty { "0" }).toDouble() else 0.0
            return v * 10 -
                (if (n.contains("thinking")) 50 else 0) -
                (if (n.contains("preview")) 3 else 0) +
                (if (Regex("live|native-audio").containsMatchIn(n)) 1 else 0)
        }

        /** Returns the best model supporting bidiGenerateContent, or null. */
        fun pickModel(apiKey: String): String? {
            var token = ""
            val found = ArrayList<String>()
            for (page in 0 until 4) {
                var url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100"
                if (token.isNotEmpty()) url += "&pageToken=$token"
                val req = Request.Builder().url(url).header("x-goog-api-key", apiKey).build()
                val body = try {
                    rest.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                } catch (e: Exception) {
                    null
                } ?: return null
                val j = JSONObject(body)
                val arr = j.optJSONArray("models") ?: break
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var ok = false
                    for (k in 0 until methods.length()) if (methods.optString(k) == "bidiGenerateContent") ok = true
                    if (!ok) continue
                    val name = m.optString("name").removePrefix("models/")
                    if (Regex("translate|transcribe|tts|image|embed").containsMatchIn(name)) continue
                    found.add(name)
                }
                token = j.optString("nextPageToken", "")
                if (token.isEmpty()) break
            }
            return found.maxByOrNull { score(it) }
        }
    }

    @Volatile var ready = false
    @Volatile private var closed = false
    private var ws: WebSocket? = null

    fun connect(setup: JSONObject) {
        val req = Request.Builder().url("$URL?key=$apiKey").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("setup", setup).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handle(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                if (!closed) {
                    closed = true
                    l.onClosed(code, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                if (!closed) {
                    closed = true
                    l.onClosed(-1, t.message ?: "failure")
                }
            }
        })
    }

    private fun handle(raw: String) {
        val m = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return
        }
        if (m.has("setupComplete")) {
            ready = true
            l.onSetupComplete()
        }
        val sc = m.optJSONObject("serverContent")
        if (sc != null) {
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val d = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    if (d.has("data")) l.onAudio(Base64.decode(d.getString("data"), Base64.DEFAULT))
                }
            }
            sc.optJSONObject("inputTranscription")?.let { if (it.has("text")) l.onInputText(it.getString("text")) }
            sc.optJSONObject("outputTranscription")?.let { if (it.has("text")) l.onOutputText(it.getString("text")) }
            if (sc.optBoolean("interrupted", false)) l.onInterrupted()
            if (sc.optBoolean("turnComplete", false)) l.onTurnComplete()
        }
        val calls = m.optJSONObject("toolCall")?.optJSONArray("functionCalls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                l.onToolCall(c.optString("id"), c.optString("name"), c.optJSONObject("args") ?: JSONObject())
            }
        }
        m.optJSONObject("sessionResumptionUpdate")?.let {
            if (it.optBoolean("resumable", true) && it.has("newHandle")) l.onResumeHandle(it.getString("newHandle"))
        }
        if (m.has("goAway")) l.onGoAway()
    }

    fun sendAudio(b: ByteArray) {
        if (!ready) return
        val w = ws ?: return
        if (w.queueSize() > 512 * 1024) return
        val b64 = Base64.encodeToString(b, Base64.NO_WRAP)
        w.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    fun sendVideo(jpeg: ByteArray) {
        if (!ready) return
        val w = ws ?: return
        if (w.queueSize() > 512 * 1024) return
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        w.send("{\"realtimeInput\":{\"video\":{\"data\":\"$b64\",\"mimeType\":\"image/jpeg\"}}}")
    }

    fun sendText(t: String) {
        if (!ready) return
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
    }

    fun sendToolResponse(id: String, name: String, response: JSONObject) {
        val fr = JSONObject().put("id", id).put("name", name).put("response", response)
        ws?.send(
            JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", JSONArray().put(fr))
            ).toString()
        )
    }

    fun close() {
        closed = true
        ready = false
        try {
            ws?.close(1000, "bye")
        } catch (e: Exception) {
        }
    }
}
