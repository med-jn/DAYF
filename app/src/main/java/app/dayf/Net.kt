package app.dayf

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * One place for every outgoing request that must not fail in front of the user:
 * automatic retries with growing delays, honouring Retry-After, and plain-Arabic messages instead of HTTP codes.
 */
object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** For Gemini: a single answer can take a while, but never longer than this. */
    val slowClient: OkHttpClient = client.newBuilder()
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(190, TimeUnit.SECONDS)
        .build()

    class Resp(val code: Int, val type: String, val bytes: ByteArray, val url: String) {
        val ok get() = code in 200..299
        val text get() = String(bytes, Charsets.UTF_8)
    }

    fun retryable(code: Int) = code == 408 || code == 425 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504 || code == 522 || code == 524

    fun pause(attempt: Int, retryAfter: String? = null) {
        val ra = retryAfter?.trim()?.toLongOrNull()
        val ms = if (ra != null) (ra * 1000).coerceIn(500, 8000) else (attempt * attempt * 700L).coerceAtMost(6000)
        try { Thread.sleep(ms) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /**
     * GET with automatic retries on timeouts, connection drops and 429/5xx.
     * Returns the last response (even if not successful) or null if the network never answered.
     */
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 30 * 1024 * 1024,
        tries: Int = 3,
        http: OkHttpClient = client
    ): Resp? {
        var last: Resp? = null
        for (attempt in 1..tries) {
            try {
                val rb = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "ar,en;q=0.8")
                for ((k, v) in headers) rb.header(k, v)
                var retryAfter: String? = null
                var again = false
                http.newCall(rb.build()).execute().use { r ->
                    val ct = r.header("Content-Type") ?: ""
                    val out = ByteArrayOutputStream()
                    val ins = r.body?.byteStream()
                    if (ins != null) {
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > maxBytes) break
                        }
                    }
                    last = Resp(r.code, ct.lowercase(), out.toByteArray(), r.request.url.toString())
                    if (retryable(r.code) && attempt < tries) {
                        retryAfter = r.header("Retry-After")
                        again = true
                    }
                }
                if (!again) return last
                pause(attempt, retryAfter)
            } catch (e: IOException) {
                AppLog.add("شبكة: ${e.javaClass.simpleName} عند $url (محاولة $attempt)")
                if (attempt < tries) pause(attempt)
            }
        }
        return last
    }

    // ------------------------------------------------------------------ friendly messages
    const val DB_UPDATE_HINT = "الخدمة تحتاج إلى تحديث قصير. أعد المحاولة بعد قليل"

    fun friendlyHttp(code: Int, body: String): String {
        val b = body.lowercase()
        return when {
            b.contains("schema cache") || (b.contains("column") && b.contains("does not exist")) ||
                b.contains("could not find the function") || b.contains("source_chunks") && b.contains("does not exist") -> DB_UPDATE_HINT
            code == 401 || b.contains("jwt") -> "انتهت جلسة الدخول، سجّل الدخول من جديد"
            code == 403 || b.contains("42501") -> "لا تملك صلاحية تنفيذ هذا الإجراء"
            code == 404 -> "العنصر المطلوب غير موجود"
            code == 409 -> "هذا العنصر موجود مسبقاً"
            code == 408 || code == 429 -> "الخدمة مشغولة قليلاً، أعد المحاولة بعد لحظات"
            code in 500..599 -> "الخدمة غير متاحة مؤقتاً، أعد المحاولة بعد قليل"
            code == 400 || code == 422 -> "تعذّر تنفيذ الطلب، راجع البيانات المدخلة"
            else -> "تعذّر تنفيذ العملية، أعد المحاولة"
        }
    }

    fun friendly(e: Throwable): String = when (e) {
        is UnknownHostException, is ConnectException -> "لا يوجد اتصال بالإنترنت حالياً"
        is SocketTimeoutException -> "الاتصال بطيء، أعد المحاولة بعد قليل"
        is IOException -> "انقطع الاتصال، أعد المحاولة"
        else -> "حدث خطأ غير متوقع، أعد المحاولة"
    }

    /** True when the problem is on the network, not in the request: worth retrying silently. */
    fun transient(e: Throwable) = e is IOException
}
