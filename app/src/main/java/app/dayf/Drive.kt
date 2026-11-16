package app.dayf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The user's own Google Drive: audio files are stored in a "TikGuest" folder there. The drive.file scope only lets the app see files it created itself.
 * One consent screen the first time; afterwards tokens are obtained silently.
 */
object Drive {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val REQ_CODE = 44
    private val jsonT = "application/json; charset=utf-8".toMediaType()

    @Volatile private var tok = ""
    @Volatile private var tokAt = 0L
    private var pending: ((Boolean) -> Unit)? = null

    /** Why the last download failed: deleted | auth | net. Never shown raw. */
    @Volatile var problem = ""

    private val http = Net.client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    fun connected(ctx: Context) = Prefs.get(ctx, "drive_ok") == "1"

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    private fun save(ctx: Context, token: String?) {
        if (token.isNullOrBlank()) return
        tok = token
        tokAt = System.currentTimeMillis()
        Prefs.putNow(ctx, "drive_ok", "1")
    }

    /** Starts the one-time consent screen (call from the UI thread). cb(true) once Drive is usable. */
    fun connect(act: Activity, cb: (Boolean) -> Unit) {
        Identity.getAuthorizationClient(act).authorize(request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    val pi = r.pendingIntent
                    if (pi == null) {
                        cb(false)
                    } else {
                        pending = cb
                        try {
                            act.startIntentSenderForResult(pi.intentSender, REQ_CODE, null, 0, 0, 0)
                        } catch (e: IntentSender.SendIntentException) {
                            pending = null
                            cb(false)
                        }
                    }
                } else {
                    save(act, r.accessToken)
                    cb(!r.accessToken.isNullOrBlank())
                }
            }
            .addOnFailureListener { e ->
                AppLog.add("Drive: تعذّر طلب الإذن (${e.javaClass.simpleName})")
                cb(false)
            }
    }

    /** Result of the consent screen (called from MainActivity.onActivityResult). */
    fun onResult(act: Activity, data: Intent?) {
        val cb = pending
        pending = null
        var ok = false
        try {
            val r = Identity.getAuthorizationClient(act).getAuthorizationResultFromIntent(data)
            save(act, r.accessToken)
            ok = !r.accessToken.isNullOrBlank()
        } catch (e: Exception) {
            AppLog.add("Drive: لم يكتمل الإذن (${e.javaClass.simpleName})")
        }
        cb?.invoke(ok)
    }

    /** Silent token; must not be called on the main thread. Null when Drive is not connected or the network failed. */
    fun token(ctx: Context): String? {
        if (!connected(ctx)) return null
        val now = System.currentTimeMillis()
        if (tok.isNotBlank() && now - tokAt < 50 * 60_000L) return tok
        return try {
            val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()), 25, TimeUnit.SECONDS)
            if (r.hasResolution()) {
                Prefs.putNow(ctx, "drive_ok", "0") // the user revoked the permission: ask again next time
                null
            } else {
                save(ctx, r.accessToken)
                r.accessToken
            }
        } catch (e: Exception) {
            AppLog.add("Drive: تعذّر تجديد الإذن (${e.javaClass.simpleName})")
            null
        }
    }

    private fun get(ctx: Context, url: String): Net.Resp? {
        for (i in 0..1) {
            val t = token(ctx) ?: return null
            val r = Net.get(url, mapOf("Authorization" to "Bearer $t"), tries = 2) ?: return null
            if (r.code == 401) {
                tok = ""
                continue
            }
            return r
        }
        return null
    }

    /** The app's own "TikGuest" folder in the user's Drive (created once, remembered). */
    fun folderId(ctx: Context): String? {
        Prefs.get(ctx, "drive_folder").takeIf { it.isNotBlank() }?.let { return it }
        val q = URLEncoder.encode("name='TikGuest' and mimeType='application/vnd.google-apps.folder' and trashed=false", "UTF-8")
        var id = ""
        val r = get(ctx, "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&pageSize=1")
        if (r != null && r.ok) {
            try { id = JSONObject(r.text).optJSONArray("files")?.optJSONObject(0)?.optString("id") ?: "" } catch (e: JSONException) { }
        }
        if (id.isBlank()) {
            val t = token(ctx) ?: return null
            try {
                val body = JSONObject().put("name", "TikGuest").put("mimeType", "application/vnd.google-apps.folder").toString()
                val rq = Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id")
                    .header("Authorization", "Bearer $t").post(body.toRequestBody(jsonT)).build()
                http.newCall(rq).execute().use { resp ->
                    if (resp.isSuccessful) id = JSONObject(resp.body?.string() ?: "{}").optString("id")
                }
            } catch (e: Exception) {
                AppLog.add("Drive: تعذّر إنشاء المجلد (${e.javaClass.simpleName})")
            }
        }
        if (id.isBlank()) return null
        Prefs.putNow(ctx, "drive_folder", id)
        return id
    }

    /** Uploads a file to the app folder and returns its Drive id (resumable upload, retried). */
    fun upload(ctx: Context, f: File, name: String, mime: String): String? {
        for (attempt in 1..3) {
            val t = token(ctx) ?: return null
            val parent = folderId(ctx) ?: return null
            try {
                val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent)).toString()
                val start = Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id")
                    .header("Authorization", "Bearer $t")
                    .header("X-Upload-Content-Type", mime)
                    .header("X-Upload-Content-Length", f.length().toString())
                    .post(meta.toRequestBody(jsonT)).build()
                var loc: String? = null
                var code = 0
                http.newCall(start).execute().use { r ->
                    code = r.code
                    if (r.isSuccessful) loc = r.header("Location")
                }
                if (code == 401) { tok = ""; continue }
                if (code == 404) { Prefs.putNow(ctx, "drive_folder", ""); continue } // the folder was deleted: recreate it
                val u = loc
                if (u != null) {
                    val put = Request.Builder().url(u).header("Authorization", "Bearer $t").put(f.asRequestBody(mime.toMediaType())).build()
                    var id = ""
                    http.newCall(put).execute().use { r ->
                        if (r.isSuccessful) id = JSONObject(r.body?.string() ?: "{}").optString("id")
                    }
                    if (id.isNotBlank()) return id
                } else if (!Net.retryable(code)) return null
            } catch (e: IOException) {
                AppLog.add("Drive: انقطع الرفع (${e.javaClass.simpleName})")
            } catch (e: JSONException) {
            }
            Net.pause(attempt)
        }
        return null
    }

    /** Local cached copy of a Drive file (downloaded on first use). Null on failure; see problemText(). */
    fun download(ctx: Context, fileId: String): File? {
        problem = ""
        val dir = File(ctx.cacheDir, "media").apply { mkdirs() }
        val dest = File(dir, fileId.filter { it.isLetterOrDigit() || it == '_' || it == '-' })
        if (dest.exists() && dest.length() > 0) return dest
        for (attempt in 1..3) {
            val t = token(ctx)
            if (t == null) {
                downloadPublic(ctx, fileId, dest)?.let { return it }
                problem = if (connected(ctx)) "net" else "auth"
                return null
            }
            val tmp = File(dir, dest.name + ".part")
            try {
                val rq = Request.Builder().url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                    .header("Authorization", "Bearer $t").build()
                var code = 0
                http.newCall(rq).execute().use { r ->
                    code = r.code
                    if (r.isSuccessful) r.body?.byteStream()?.use { ins -> tmp.outputStream().use { o -> ins.copyTo(o) } }
                }
                if (code in 200..299 && tmp.length() > 0 && tmp.renameTo(dest)) return dest
                if (code == 401) { tok = ""; continue }
                if (code == 404 || code == 403) {
                    tmp.delete()
                    // not ours: it may be a friend's file shared with the link
                    downloadPublic(ctx, fileId, dest)?.let { return it }
                    problem = "deleted"
                    return null
                }
                tmp.delete()
            } catch (e: IOException) {
                problem = "net"
                tmp.delete()
            }
            Net.pause(attempt)
        }
        if (problem.isBlank()) problem = "net"
        return null
    }

    // ---------------------------------------------------------------- sharing media with friends
    @Volatile private var apiKey = ""

    /** Public API key (restricted to the Drive API) kept in the app_config table, fetched once. */
    private fun publicKey(ctx: Context): String {
        if (apiKey.isNotBlank()) return apiKey
        val k = Prefs.get(ctx, "drive_api_key")
        if (k.isNotBlank()) { apiKey = k; return k }
        val v = Repo.supa(ctx)?.appConfig("drive_api_key") ?: ""
        if (v.isNotBlank()) {
            Prefs.putNow(ctx, "drive_api_key", v)
            apiKey = v
        }
        return apiKey
    }

    /** The owner lets "anyone with the link" read a file the app created, so shared personas can play it. */
    fun makePublic(ctx: Context, fileId: String): Boolean {
        val t = token(ctx) ?: return false
        return try {
            val body = JSONObject().put("role", "reader").put("type", "anyone").toString()
            val rq = Request.Builder().url("https://www.googleapis.com/drive/v3/files/$fileId/permissions")
                .header("Authorization", "Bearer $t").post(body.toRequestBody(jsonT)).build()
            http.newCall(rq).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            AppLog.add("Drive: تعذّر تفعيل المشاركة (${e.javaClass.simpleName})")
            false
        }
    }

    fun removePublic(ctx: Context, fileId: String) {
        val t = token(ctx) ?: return
        try {
            val rq = Request.Builder().url("https://www.googleapis.com/drive/v3/files/$fileId/permissions/anyoneWithLink")
                .header("Authorization", "Bearer $t").delete().build()
            http.newCall(rq).execute().close()
        } catch (e: Exception) {
        }
    }

    /** Reads a file a friend made link-shared (no account needed, just the public key). */
    fun downloadPublic(ctx: Context, fileId: String, dest: File): File? {
        val key = publicKey(ctx)
        if (key.isBlank()) return null
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            val rq = Request.Builder().url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media&key=$key").build()
            var code = 0
            http.newCall(rq).execute().use { r ->
                code = r.code
                if (r.isSuccessful) r.body?.byteStream()?.use { ins -> tmp.outputStream().use { o -> ins.copyTo(o) } }
            }
            if (code in 200..299 && tmp.length() > 0 && tmp.renameTo(dest)) dest else { tmp.delete(); null }
        } catch (e: IOException) {
            tmp.delete()
            null
        }
    }

    /** Best effort: removes the file this app created when its source is deleted. */
    fun delete(ctx: Context, fileId: String) {
        Thread {
            try {
                val t = token(ctx) ?: return@Thread
                val rq = Request.Builder().url("https://www.googleapis.com/drive/v3/files/$fileId").header("Authorization", "Bearer $t").delete().build()
                http.newCall(rq).execute().close()
                File(File(ctx.cacheDir, "media"), fileId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }).delete()
            } catch (e: Exception) {
            }
        }.start()
    }

    fun problemText(): String = when (problem) {
        "deleted" -> "ملف الصوت لم يعد موجوداً في Drive (ربما حُذف)"
        "auth" -> "التطبيق غير مرتبط بـ Drive حالياً"
        else -> "تعذّر تحميل الصوت الآن لأن الاتصال ضعيف"
    }
}
