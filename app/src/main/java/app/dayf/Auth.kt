package app.dayf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Sign in with Google through Supabase Auth, PKCE flow in the system browser.
 * No Google client library, no client secret on the device.
 */
object Auth {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val media = "application/json".toMediaType()
    private val rnd = SecureRandom()
    @Volatile private var cached: JSONObject? = null

    private fun b64url(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun session(c: Context): JSONObject? {
        cached?.let { return it }
        val s = SecureStore.get(c, "session")
        if (s.isBlank()) return null
        return try {
            JSONObject(s).also { cached = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun save(c: Context, j: JSONObject) {
        if (!j.has("expires_at") || j.optLong("expires_at", 0) == 0L) {
            j.put("expires_at", System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600))
        }
        cached = j
        SecureStore.put(c, "session", j.toString())
    }

    fun isSignedIn(c: Context): Boolean = session(c) != null
    fun email(c: Context): String = session(c)?.optJSONObject("user")?.optString("email") ?: ""
    fun userId(c: Context): String = session(c)?.optJSONObject("user")?.optString("id") ?: ""

    /** A valid access token (refreshed when near expiry). Blocking: call from a background thread. */
    @Synchronized
    fun token(c: Context): String? {
        val s = session(c) ?: return null
        val exp = s.optLong("expires_at", 0)
        if (exp - System.currentTimeMillis() / 1000 > 60) return s.optString("access_token")
        val rt = s.optString("refresh_token")
        if (rt.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("${Config.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", Config.SUPABASE_KEY)
                .post(JSONObject().put("refresh_token", rt).toString().toRequestBody(media))
                .build()
            http.newCall(req).execute().use { r ->
                val t = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    AppLog.add("تجديد الجلسة فشل: HTTP ${r.code}")
                    if (r.code == 400 || r.code == 401) signOut(c)
                    null
                } else {
                    val j = JSONObject(t)
                    save(c, j)
                    j.optString("access_token")
                }
            }
        } catch (e: Exception) {
            s.optString("access_token")
        }
    }

    fun beginLogin(a: Activity) {
        val verifier = b64url(ByteArray(48).also { rnd.nextBytes(it) })
        Prefs.putNow(a, "pkce_verifier", verifier)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val url = Uri.parse("${Config.SUPABASE_URL}/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", Config.REDIRECT)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .build()
        AppLog.add("فتح صفحة تسجيل الدخول بجوجل")
        a.startActivity(Intent(Intent.ACTION_VIEW, url))
    }

    /** Called with the deep link the browser returns to. The callback may arrive on a background thread. */
    fun complete(c: Context, uri: Uri, done: (Boolean, String) -> Unit) {
        val code = uri.getQueryParameter("code")
        if (code == null) {
            done(false, uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error") ?: "لم يصل رمز الدخول")
            return
        }
        val verifier = Prefs.get(c, "pkce_verifier")
        Thread {
            try {
                val body = JSONObject().put("auth_code", code).put("code_verifier", verifier)
                val req = Request.Builder()
                    .url("${Config.SUPABASE_URL}/auth/v1/token?grant_type=pkce")
                    .header("apikey", Config.SUPABASE_KEY)
                    .post(body.toString().toRequestBody(media))
                    .build()
                http.newCall(req).execute().use { r ->
                    val t = r.body?.string() ?: ""
                    if (!r.isSuccessful) done(false, "HTTP ${r.code} ${t.take(160)}")
                    else {
                        save(c, JSONObject(t))
                        Prefs.putNow(c, "pkce_verifier", "")
                        done(true, "")
                    }
                }
            } catch (e: Exception) {
                done(false, e.message ?: "error")
            }
        }.start()
    }

    fun signOut(c: Context) {
        cached = null
        SecureStore.remove(c, "session")
        Repo.reset()
    }
}

/** Receives com.tikguest://auth-callback after Google sign-in. */
class AuthCallbackActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppLog.installCrashHandler(this)
        setContentView(View(this).also { it.setBackgroundColor(Ui.BG) })
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        Auth.complete(this, uri) { ok, msg ->
            runOnUiThread {
                AppLog.add(if (ok) "تسجيل الدخول نجح" else "تسجيل الدخول فشل: $msg")
                if (!ok) Toast.makeText(this, "تعذّر تسجيل الدخول: $msg", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
        }
    }
}
