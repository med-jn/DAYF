package app.dayf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Plain-Arabic messages. The user never sees HTTP codes. */
object Msg {
    const val BUSY = "الخدمة مشغولة، ستُعاد المحاولة تلقائياً"
    const val UNREACHABLE = "تعذّر الوصول إلى الموقع، ستُعاد المحاولة تلقائياً"
    const val BLOCKED = "الموقع يمنع القراءة الآلية. جرّب رفع الصفحة كملف PDF أو نص"
    const val MISSING = "هذه الصفحة غير متاحة أو حُذفت. تحقّق من الرابط أو ارفع نسخة منها كملف"
    const val NEED_KEY = "هذا النوع يحتاج مفتاح Gemini. أضفه من الإعدادات ثم اضغط إعادة المحاولة"
    const val BAD_KEY = "مفتاح Gemini غير صالح أو منتهي. حدّثه من الإعدادات ثم اضغط إعادة المحاولة"
    const val EMPTY = "لم أجد نصاً كافياً في هذا المصدر. جرّب رفعه كملف PDF أو نص"
    const val UNSUPPORTED = "هذه الصيغة غير مدعومة بعد. حوّل الملف إلى PDF أو Word أو نص"
    const val TOO_BIG = "الملف كبير على الجهاز. جرّب ملفاً أصغر أو قسّمه إلى أجزاء"
    const val GENERIC = "تعذّرت القراءة مؤقتاً، ستُعاد المحاولة تلقائياً"
    const val VIDEO = "تعذّرت قراءة الفيديو (قد يكون خاصاً أو مقيّداً). تأكد أنه عام"
    const val NEED_DRIVE = "لحفظ الصوتيات اربط حسابك بـ Google Drive ثم اضغط إعادة المحاولة"
    const val AUDIO_ONLY = "هذا ملف فيديو. استخرج صوته بصيغة mp3 أو m4a ثم ارفعه"
    const val COPY = "تعذّر نسخ الملف من جهازك، اختره من جديد"
}

object SrcType {
    fun label(t: String) = when (t) {
        "article" -> "مقال"
        "docs" -> "توثيق"
        "site" -> "موقع"
        "book" -> "كتاب"
        "document" -> "مستند"
        "sheet" -> "جدول"
        "video" -> "فيديو"
        "audio" -> "صوت"
        "image" -> "صورة"
        "text" -> "نص"
        "library" -> "مكتبة"
        else -> "مرجع"
    }

    fun icon(t: String): Ic = when (t) {
        "video" -> Ic.STUDIO
        "audio" -> Ic.HEADPHONES
        "image" -> Ic.EYE
        "library" -> Ic.DATABASE
        "book" -> Ic.BOOK
        "document", "text", "sheet" -> Ic.FILE
        else -> Ic.LINK
    }

    /** The type shown in the app: old rows keep working even if they were saved before types existed. */
    fun of(o: JSONObject): String = when (o.strOr("kind")) {
        "youtube" -> "video"
        "supabase" -> "library"
        else -> o.strOr("type", "article").ifBlank { "article" }
    }
}

object Ingest {
    private const val MAX_ATTEMPTS = 6
    private val DELAYS = longArrayOf(0, 30, 120, 600, 1800, 7200) // seconds before attempt n+1
    private const val MAX_CHARS = 3_000_000
    private const val MAX_FILE = 80L * 1024 * 1024
    private const val INLINE_MAX = 3L * 1024 * 1024
    private const val MAX_SITE_PAGES = 60

    private val pool = Executors.newFixedThreadPool(2)
    private val running = ConcurrentHashMap.newKeySet<String>()

    sealed class Out {
        class Ok(val md: String, val title: String, val type: String, val via: String, val pages: Int = 0, val note: String = "") : Out()
        class Retry(val msg: String, val maxAttempts: Int = MAX_ATTEMPTS) : Out()
        class Fail(val msg: String) : Out()
    }

    // ------------------------------------------------------------------ small helpers
    private fun iso(offsetSec: Long = 0): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(System.currentTimeMillis() + offsetSec * 1000))
    }

    fun localFile(ctx: Context, id: String): File = File(File(ctx.filesDir, "src").apply { mkdirs() }, id)

    private fun host(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: ""

    fun normalize(raw: String): String {
        var u = raw.trim().replace(Regex("\\s+"), "")
        if (u.isNotEmpty() && !u.startsWith("http", true)) u = "https://$u"
        return u
    }

    /** Links to Google Docs / Drive / GitHub / Dropbox are rewritten to a direct, readable address. */
    fun rewrite(url: String): String {
        Regex("docs\\.google\\.com/document/d/([\\w-]+)").find(url)?.let { return "https://docs.google.com/document/d/${it.groupValues[1]}/export?format=html" }
        Regex("docs\\.google\\.com/spreadsheets/d/([\\w-]+)").find(url)?.let { return "https://docs.google.com/spreadsheets/d/${it.groupValues[1]}/export?format=csv" }
        Regex("docs\\.google\\.com/presentation/d/([\\w-]+)").find(url)?.let { return "https://docs.google.com/presentation/d/${it.groupValues[1]}/export/pptx" }
        Regex("drive\\.google\\.com/file/d/([\\w-]+)").find(url)?.let { return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}" }
        Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url)?.let { return "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}" }
        if (url.contains("dropbox.com") && url.contains("dl=0")) return url.replace("dl=0", "dl=1")
        return url
    }

    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "3gp", "wma", "mka")
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "wmv", "flv")
    private val TXT = setOf("txt", "md", "markdown", "text", "log", "json", "xml", "yaml", "yml", "rtf", "srt", "vtt")

    private fun extOf(name: String): String = name.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase().take(6)

    private fun typeByExt(ext: String): String? = when (ext) {
        "pdf", "docx", "doc", "pptx", "ppt", "odt" -> "document"
        "epub" -> "book"
        "xlsx", "xlsm", "csv", "tsv", "xls" -> "sheet"
        "html", "htm" -> "article"
        in AUDIO -> "audio"
        in VIDEO_EXT -> "video"
        in TXT -> "text"
        in IMG -> "image"
        else -> null
    }

    private fun docsLike(url: String): Boolean {
        val l = url.lowercase()
        return Regex("//docs?\\.|readthedocs|gitbook|/docs?/|/guide|/manual|/handbook|/wiki/|/reference|/tutorial|/learn/|/api/").containsMatchIn(l)
    }

    /** Smart classification before any download: (kind, type). Refined later from the real content. */
    fun classify(url: String, site: Boolean): Pair<String, String> {
        val h = host(url)
        if (h.endsWith("youtube.com") || h == "youtu.be" || h.endsWith("youtube-nocookie.com")) return "youtube" to "video"
        if (site) return "site" to (if (docsLike(url)) "docs" else "site")
        val ext = extOf(url.toHttpUrlOrNull()?.encodedPath ?: url)
        val t = typeByExt(ext)
        if (t != null && t != "article") return "web" to t
        return "web" to (if (docsLike(url)) "docs" else "article")
    }

    fun fallbackTitle(url: String, file: String): String =
        if (file.isNotBlank()) file.substringBeforeLast('.').take(80)
        else (url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')).take(70)

    // ------------------------------------------------------------------ public entry points
    /** Creates the source row at once (so the card appears) and starts reading in the background. */
    fun addLink(ctx: Context, title: String, rawUrl: String, site: Boolean, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val s = Repo.supa(app)
            val url = normalize(rawUrl)
            if (s == null || Repo.personaId.isBlank()) {
                done("سجّل الدخول أولاً")
                return@Thread
            }
            if (url.toHttpUrlOrNull() == null) {
                done("الرابط غير صالح، تأكد من كتابته كاملاً")
                return@Thread
            }
            val (kind, type) = classify(rewrite(url), site)
            val id = s.createSource(
                JSONObject().put("persona_id", Repo.personaId).put("kind", kind).put("type", type).put("title", title)
                    .put("url", url).put("status", "pending").put("progress", "في الانتظار…")
            )
            if (id == null) {
                done(s.lastError)
                return@Thread
            }
            done(null)
            process(app, id)
        }.start()
    }

    private class Picked(val id: String, val uri: Uri)

    fun displayName(ctx: Context, uri: Uri): String = fileMeta(ctx.applicationContext, uri).first

    private fun descOf(row: JSONObject): String = try {
        (row.optJSONObject("config") ?: JSONObject(row.optString("config", "{}"))).optString("desc")
    } catch (e: Exception) {
        ""
    }

    private fun fileMeta(app: Context, uri: Uri): Triple<String, Long, String> {
        var name = "ملف"
        var size = -1L
        try {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
        }
        return Triple(name, size, app.contentResolver.getType(uri) ?: "")
    }

    private fun copyLocal(app: Context, uri: Uri, id: String): Boolean = try {
        val f = localFile(app, id)
        app.contentResolver.openInputStream(uri)?.use { ins ->
            f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE) throw java.io.IOException("too big")
                    o.write(buf, 0, n)
                }
            }
        } ?: throw java.io.IOException("no stream")
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Picked files (one or hundreds): every file gets its card at once, then they are copied into the app and read one after another
     * (two readers work in parallel). Each ends up in an automatic folder by type.
     */
    fun addFiles(ctx: Context, uris: List<Uri>, title: String = "", desc: String = "", done: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val s = Repo.supa(app)
            if (s == null || Repo.personaId.isBlank()) {
                done("سجّل الدخول أولاً")
                return@Thread
            }
            val list = uris.take(300)
            val picked = ArrayList<Picked>()
            var tooBig = 0
            for (u in list) {
                val (name, size, mime) = fileMeta(app, u)
                if (size > MAX_FILE) { tooBig++; continue }
                val type = typeByExt(extOf(name)) ?: if (mime.startsWith("audio/")) "audio" else "document"
                val row = JSONObject().put("persona_id", Repo.personaId).put("kind", "file").put("type", type)
                    .put("title", if (list.size == 1) title else "")
                    .put("file_name", name).put("mime", mime).put("status", "pending").put("progress", "في الانتظار…")
                if (list.size == 1 && desc.isNotBlank()) row.put("config", JSONObject().put("desc", desc))
                val id = s.createSource(row)
                if (id != null) picked.add(Picked(id, u))
            }
            val extra = (if (uris.size > 300) " (أول 300 ملف فقط)" else "") + (if (tooBig > 0) " — تجاوز $tooBig ملفاً الحد (80 ميغابايت)" else "")
            done(if (picked.isEmpty()) (if (tooBig > 0) "الملفات أكبر من 80 ميغابايت" else s.lastError) else "بدأت قراءة ${picked.size} ملف$extra. يمكنك المتابعة")
            for (pk in picked) {
                if (copyLocal(app, pk.uri, pk.id)) process(app, pk.id)
                else s.updateSource(pk.id, JSONObject().put("status", "failed").put("progress", "").put("error", Msg.COPY))
            }
        }.start()
    }

    /** Replaces the content of an existing card with a picked file (the "alternative file" action). */
    fun addFile(ctx: Context, uri: Uri, replaceId: String?, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val s = Repo.supa(app)
            if (s == null || Repo.personaId.isBlank()) {
                done("سجّل الدخول أولاً")
                return@Thread
            }
            val (name, size, mime) = fileMeta(app, uri)
            if (size > MAX_FILE) {
                done("الملف أكبر من 80 ميغابايت. قسّمه إلى أجزاء أصغر")
                return@Thread
            }
            val type = typeByExt(extOf(name)) ?: "document"
            var id = replaceId
            if (id == null) {
                id = s.createSource(
                    JSONObject().put("persona_id", Repo.personaId).put("kind", "file").put("type", type).put("title", "")
                        .put("file_name", name).put("mime", mime).put("status", "pending").put("progress", "جارٍ نسخ الملف…")
                )
                if (id == null) {
                    done(s.lastError)
                    return@Thread
                }
            } else {
                s.updateSource(
                    id, JSONObject().put("kind", "file").put("type", type).put("file_name", name).put("mime", mime).put("status", "pending")
                        .put("progress", "جارٍ نسخ الملف…").put("attempts", 0).put("error", "").put("next_retry_at", JSONObject.NULL)
                )
            }
            if (!copyLocal(app, uri, id)) {
                s.updateSource(id, JSONObject().put("status", "failed").put("progress", "").put("error", Msg.COPY))
                done(null)
                return@Thread
            }
            done(null)
            process(app, id)
        }.start()
    }

    /** User pressed "retry": start fresh, with the attempt counter reset. */
    fun retryNow(ctx: Context, id: String) {
        val app = ctx.applicationContext
        Thread {
            val s = Repo.supa(app) ?: return@Thread
            s.updateSource(id, JSONObject().put("status", "pending").put("attempts", 0).put("error", "").put("progress", "في الانتظار…").put("next_retry_at", JSONObject.NULL))
            process(app, id)
        }.start()
    }

    /** Picks up sources that are due for an automatic retry or whose reading was interrupted (app closed, phone slept). */
    fun sweep(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val s = Repo.supa(app) ?: return@Thread
            val rows = s.listSources(Repo.personaId) ?: return@Thread
            sweepRows(app, rows)
        }.start()
    }

    fun sweepRows(ctx: Context, rows: JSONArray) {
        val now = System.currentTimeMillis()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val id = o.strOr("id")
            if (id.isBlank() || running.contains(id)) continue
            val status = o.strOr("status")
            val t = parseTime(o.strOr("next_retry_at"))
            val upd = parseTime(o.strOr("updated_at"))
            val due = status == "retry" && (t == 0L || t <= now)
            val stuck = status == "pending" && upd > 0 && now - upd > 6 * 60_000L
            val fresh = status == "pending" && upd == 0L
            if (due || stuck || fresh) process(ctx, id)
        }
    }

    private fun parseTime(s: String): Long = try {
        if (s.isBlank()) 0L else java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    // ------------------------------------------------------------------ the worker
    fun process(ctx: Context, id: String) {
        if (!running.add(id)) return
        pool.execute {
            try {
                run(ctx.applicationContext, id)
            } catch (t: Throwable) {
                AppLog.add("قراءة مصدر: ${t.javaClass.simpleName} ${t.message}")
                try {
                    Repo.supa(ctx)?.updateSource(id, JSONObject().put("status", "retry").put("progress", "").put("error", Msg.GENERIC).put("next_retry_at", iso(120)))
                } catch (x: Throwable) {
                }
            } finally {
                running.remove(id)
            }
        }
    }

    private fun run(ctx: Context, id: String) {
        val s = Repo.supa(ctx) ?: return
        val row = s.getSource(id) ?: return
        if (row.strOr("kind") == "supabase") return
        val attempts = row.intOr("attempts", 0) + 1
        s.updateSource(id, JSONObject().put("status", "pending").put("attempts", attempts).put("progress", "بدأت القراءة…").put("error", "").put("updated_at", iso()))
        var last = 0L
        val progress: (String) -> Unit = { m ->
            val t = System.currentTimeMillis()
            if (t - last > 1500) {
                last = t
                s.updateSource(id, JSONObject().put("progress", m).put("updated_at", iso()))
            }
        }
        val out: Out = try {
            read(ctx, id, row, progress)
        } catch (e: OutOfMemoryError) {
            Out.Fail(Msg.TOO_BIG)
        } catch (e: Exception) {
            AppLog.add("قراءة ${row.strOr("title")}: ${e.javaClass.simpleName} ${e.message}")
            if (Net.transient(e)) Out.Retry(Msg.UNREACHABLE) else Out.Retry(Msg.GENERIC, 3)
        }
        if (out is Out.Retry) AppLog.add("مرجع «${row.strOr("title").ifBlank { row.strOr("file_name") }}»: ${out.msg} (محاولة $attempts)")
        if (out is Out.Fail) AppLog.add("مرجع «${row.strOr("title").ifBlank { row.strOr("file_name") }}»: ${out.msg}")
        when (out) {
            is Out.Ok -> store(ctx, s, id, row, out, progress)
            is Out.Retry -> {
                if (attempts >= out.maxAttempts) {
                    s.updateSource(id, JSONObject().put("status", "failed").put("progress", "").put("error", out.msg).put("updated_at", iso()))
                } else {
                    val wait = DELAYS[attempts.coerceAtMost(DELAYS.size - 1)]
                    s.updateSource(
                        id, JSONObject().put("status", "retry").put("progress", "").put("error", out.msg)
                            .put("next_retry_at", iso(wait)).put("updated_at", iso())
                    )
                }
            }
            is Out.Fail -> s.updateSource(id, JSONObject().put("status", "failed").put("progress", "").put("error", out.msg).put("updated_at", iso()))
        }
    }

    private fun store(ctx: Context, s: Supa, id: String, row: JSONObject, ok: Out.Ok, progress: (String) -> Unit) {
        var md = ok.md.trim()
        val desc = descOf(row)
        if (desc.isNotBlank()) md = "## وصف من صاحب المرجع\n$desc\n\n$md"
        if (md.length < 80) {
            s.updateSource(id, JSONObject().put("status", "failed").put("progress", "").put("error", Msg.EMPTY).put("updated_at", iso()))
            return
        }
        var note = ok.note
        if (md.length > MAX_CHARS) {
            md = md.substring(0, md.lastIndexOf("\n\n", MAX_CHARS).let { if (it < MAX_CHARS / 2) MAX_CHARS else it })
            note = (note + " حُفظ أول ${MAX_CHARS / 1000} ألف حرف فقط").trim()
        }
        val pid = row.strOr("persona_id").ifBlank { Repo.personaId }
        val hash = Md.hash(md)
        val unchanged = hash == row.strOr("content_hash") && row.intOr("chunk_count", 0) > 0
        val chunks = Md.chunk(md)
        if (!unchanged) {
            progress("حفظ المحتوى (${chunks.size} جزء)…")
            s.deleteChunks(id)
            var i = 0
            while (i < chunks.size) {
                val part = chunks.subList(i, minOf(chunks.size, i + 40))
                if (!s.insertChunks(pid, id, part)) {
                    val err = s.lastError
                    s.deleteChunks(id)
                    if (err == Net.DB_UPDATE_HINT) s.updateSource(id, JSONObject().put("status", "failed").put("progress", "").put("error", err).put("updated_at", iso()))
                    else s.updateSource(id, JSONObject().put("status", "retry").put("progress", "").put("error", Msg.BUSY).put("next_retry_at", iso(60)).put("updated_at", iso()))
                    return
                }
                i += 40
            }
        }
        val title = row.strOr("title").ifBlank { ok.title.ifBlank { fallbackTitle(row.strOr("url"), row.strOr("file_name")) } }
        val done = JSONObject()
        if (row.strOr("folder_id").isBlank()) Folders.idFor(s, pid, Folders.nameFor(ok.type))?.let { done.put("folder_id", it) }
        s.updateSource(
            id,
            done.put("status", "ready").put("progress", "").put("error", "").put("title", title).put("type", ok.type).put("via", ok.via)
                .put("brief", if (desc.isNotBlank()) desc.take(220) else if (ok.type == "audio") md.lines().filter { !it.startsWith("#") && it.isNotBlank() }.joinToString(" ").take(260) else Md.brief(md)).put("outline", Md.outline(chunks)).put("chunk_count", chunks.size).put("char_count", md.length)
                .put("page_count", ok.pages).put("content_hash", hash).put("note", note).put("attempts", 0).put("next_retry_at", JSONObject.NULL)
                .put("fetched_at", iso()).put("updated_at", iso()).put("digest", "")
        )
        try { localFile(ctx, id).delete() } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ reading
    private fun read(ctx: Context, id: String, row: JSONObject, p: (String) -> Unit): Out {
        val kind = row.strOr("kind")
        return when (kind) {
            "file" -> {
                val f = localFile(ctx, id)
                if (!f.exists()) Out.Fail("ملف هذا المرجع لم يعد موجوداً على الجهاز. اضغط «ملف بديل» لرفعه من جديد")
                else convertFile(ctx, f, row.strOr("file_name"), row.strOr("mime"), p, id)
            }
            "youtube" -> readYoutube(ctx, row.strOr("url"), p)
            else -> readWeb(ctx, row, p)
        }
    }

    private fun gemKey(ctx: Context): String = Secrets.gemini(ctx)

    @Volatile private var modelCache: List<String> = emptyList()
    @Volatile private var modelAt = 0L

    /**
     * Models are discovered from the API (names change and old ones are retired). The list alternates full and lite models,
     * because each has its own quota and load: when the newest one is busy the next one answers.
     */
    fun models(ctx: Context, key: String): List<String> {
        val now = System.currentTimeMillis()
        if (modelCache.isEmpty() || now - modelAt > 6 * 3600_000L) {
            val ranked = LiveClient.rankTextModels(key)
            val full = ranked.filter { !it.contains("lite") }
            val lite = ranked.filter { it.contains("lite") }
            val mixed = ArrayList<String>()
            for (i in 0 until 3) {
                full.getOrNull(i)?.let { mixed.add(it) }
                lite.getOrNull(i)?.let { mixed.add(it) }
            }
            val l = mixed.distinct().take(5)
            if (l.isNotEmpty()) {
                modelCache = l
                modelAt = now
            }
        }
        if (modelCache.isNotEmpty()) return modelCache
        return listOf(Repo.textModel).filter { it.isNotBlank() }
    }

    fun forgetModels() {
        modelCache = emptyList()
        modelAt = 0
    }

    private fun fromGemFailure(o: Gem.Out, video: Boolean = false): Out {
        val low = o.err.lowercase()
        AppLog.add("Gemini رفض الطلب: ${o.code} ${o.err.take(140)}")
        if (o.code == 404 || low.contains("no longer available")) forgetModels()
        return when {
            low.contains("api key") || o.code == 401 || (o.code == 403 && low.contains("key")) -> Out.Fail(Msg.BAD_KEY)
            o.code == 400 && low.contains("block") -> Out.Fail(Msg.EMPTY)
            o.code == 400 && video -> Out.Retry(Msg.VIDEO, 2)
            o.code == 0 || Net.retryable(o.code) -> Out.Retry(Msg.BUSY)
            else -> Out.Retry(Msg.GENERIC, 3)
        }
    }

    // ---- files (uploaded or downloaded)
    private fun readText(f: File): String {
        val bytes = f.readBytes()
        val s = String(bytes, Charsets.UTF_8)
        val bad = s.count { it == '\uFFFD' }
        return if (s.isNotEmpty() && bad > s.length * 0.002) String(bytes, charset("windows-1256")) else s
    }

    private fun bookOrDoc(md: String, pages: Int): String = if (pages >= 60 || md.length > 150_000) "book" else "document"

    fun convertFile(ctx: Context, f: File, name: String, mime: String, p: (String) -> Unit, srcId: String = ""): Out {
        val ext = extOf(name)
        val m = mime.lowercase()
        p("تحويل الملف إلى نص منظّم…")
        return when {
            ext in AUDIO || m.startsWith("audio/") -> readAudio(ctx, f, name, m, srcId, p)
            ext in VIDEO_EXT || m.startsWith("video/") -> Out.Fail(Msg.AUDIO_ONLY)
            ext == "pdf" || m.contains("pdf") -> readPdf(ctx, f, name, p)
            ext == "docx" || m.contains("wordprocessingml") -> {
                val md = Md.docx(f)
                Out.Ok(md, "", if (md.length > 150_000) "book" else "document", "file")
            }
            ext == "pptx" || m.contains("presentationml") -> Out.Ok(Md.pptx(f), "", "document", "file")
            ext == "xlsx" || ext == "xlsm" || m.contains("spreadsheetml") -> Out.Ok(Md.xlsx(f), "", "sheet", "file")
            ext == "epub" || m.contains("epub") -> {
                val r = Md.epub(f)
                Out.Ok(r.second, r.first, "book", "file")
            }
            ext == "csv" || ext == "tsv" || m.contains("csv") -> Out.Ok(Md.csvToMd(readText(f)), "", "sheet", "file")
            ext == "html" || ext == "htm" || m.contains("html") -> {
                val pg = Md.fromHtml(f.readBytes(), "http://file.local/", 0, false)
                Out.Ok(pg.md, pg.title, "article", "file")
            }
            ext in IMG || m.startsWith("image/") -> readImage(ctx, f, if (m.startsWith("image/")) m else "image/" + (if (ext == "jpg") "jpeg" else ext), p, name, srcId)
            ext == "doc" || ext == "ppt" || ext == "xls" || ext == "odt" -> Out.Fail(Msg.UNSUPPORTED)
            ext in TXT || m.startsWith("text/") || ext.isEmpty() -> Out.Ok(Md.fromPlain(readText(f)), "", "text", "file")
            else -> {
                // unknown extension: accept it only if it looks like text
                val head = f.inputStream().use { it.readNBytesCompat(2000) }
                val printable = head.count { it.toInt() in 9..13 || it.toInt() >= 32 || it.toInt() < 0 }
                if (head.isNotEmpty() && printable > head.size * 0.95) Out.Ok(Md.fromPlain(readText(f)), "", "text", "file") else Out.Fail(Msg.UNSUPPORTED)
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return buf.copyOf(got)
    }

    // ---- PDF: converted in windows of pages so nothing is cut short and no single call can time out
    private val PAGE_MARK = Regex("\\[\\[p(\\d+)]]")

    private fun pdfPrompt(from: Int, to: Int) =
        "You are a document-to-Markdown converter. Convert pages $from to $to of the attached document to Markdown, faithfully and completely. " +
            "Keep the original language and EVERY word, number, name and date. Do NOT summarise, shorten, translate, explain or comment. " +
            "Use # headings for titles, chapters and sections; Markdown tables for tables; lists for lists. " +
            "For a chart, figure or picture that carries information write one line [شكل: short description]. Skip page headers, footers and page numbers that repeat. " +
            "Begin every page with a line of exactly [[p<n>]] where <n> is that page's number in the file (1 = first page). " +
            "If the document has no pages after page $to, finish with a last line of exactly [[END]]."

    private fun readPdf(ctx: Context, f: File, name: String, p: (String) -> Unit): Out {
        val key = gemKey(ctx)
        if (key.isBlank()) return Out.Fail(Msg.NEED_KEY)
        val models = models(ctx, key)
        if (models.isEmpty()) return Out.Fail(Msg.NEED_KEY)
        val doc: JSONObject = if (f.length() <= INLINE_MAX) Gem.inlinePart(f.readBytes(), "application/pdf")
        else {
            p("رفع الملف للقراءة…")
            val uri = Gem.upload(key, f, "application/pdf", name) ?: return Out.Retry(Msg.BUSY)
            Gem.filePart(uri, "application/pdf")
        }
        val win = 10
        var start = 1
        var lastPage = 0
        var passes = 0
        var emptyStreak = 0
        val all = StringBuilder()
        var partial = ""
        while (passes < 80) {
            passes++
            p("تحويل الصفحات $start–${start + win - 1}…")
            val o = Gem.generate(key, models, listOf(doc, Gem.textPart(pdfPrompt(start, start + win - 1))))
            if (!o.ok) {
                if (all.isEmpty()) return fromGemFailure(o)
                partial = "قُرئت الصفحات حتى $lastPage فقط. اضغط تحديث لإعادة القراءة كاملة"
                break
            }
            val ended = o.text.contains("[[END]]")
            var body = o.text.replace("[[END]]", "").trim()
            val marks = PAGE_MARK.findAll(body).map { it.groupValues[1].toInt() }.toList()
            if (marks.isEmpty()) {
                if (ended || ++emptyStreak >= 2) {
                    if (body.length > 200 && lastPage == 0) all.append(body).append("\n\n")
                    break
                }
                continue
            }
            if (marks.max() <= lastPage) break // the model started repeating: nothing new
            var next = marks.max() + 1
            if (o.finish == "MAX_TOKENS" && marks.size >= 2) {
                // the last page may be cut: drop it and read it again in the next window
                val cut = body.lastIndexOf("[[p${marks.last()}]]")
                if (cut > 0) body = body.substring(0, cut).trim()
                next = marks.last()
            }
            all.append(body).append("\n\n")
            lastPage = maxOf(lastPage, next - 1)
            if (ended && o.finish != "MAX_TOKENS") break
            if (next <= start) next = start + win
            start = next
        }
        val md = all.toString().trim()
        if (md.length < 80) return Out.Retry(Msg.EMPTY, 2)
        return Out.Ok(md, "", bookOrDoc(md, lastPage), "gemini", lastPage, partial)
    }

    private fun readImage(ctx: Context, f: File, mime: String, p: (String) -> Unit, name: String = "", srcId: String = ""): Out {
        val key = gemKey(ctx)
        if (key.isBlank()) return Out.Fail(Msg.NEED_KEY)
        val models = models(ctx, key)
        if (f.length() > 15L * 1024 * 1024) return Out.Fail(Msg.TOO_BIG)
        p("قراءة الصورة…")
        val prompt = "Extract ALL the text in this image exactly as written (OCR), keeping its language, as Markdown with headings and tables where they exist. " +
            "Then, only if the image also has charts, diagrams or photos, add a section '## وصف الصورة' with a short factual description. No commentary."
        val o = Gem.generate(key, models, listOf(Gem.inlinePart(f.readBytes(), mime), Gem.textPart(prompt)), 12000)
        if (!o.ok) return fromGemFailure(o)
        // the original picture is kept in the user's Drive (best effort: the text above is already enough to use it)
        if (srcId.isNotBlank() && Drive.connected(ctx)) {
            try {
                val s = Repo.supa(ctx)
                if (s != null && s.getSource(srcId)?.strOr("drive_file_id").isNullOrBlank()) {
                    p("حفظ الصورة في Drive…")
                    Drive.upload(ctx, f, name.ifBlank { "image" }, mime)?.let { s.updateSource(srcId, JSONObject().put("drive_file_id", it).put("media_mime", mime)); Sharing.onMediaUploaded(ctx, s, it) }
                }
            } catch (e: Exception) {
                AppLog.add("حفظ الصورة في Drive: ${e.javaClass.simpleName}")
            }
        }
        return Out.Ok(o.text.trim(), "", "image", "gemini")
    }

    // ---- audio: the original goes to the user's Drive (so the agent can play it), the notes go to the database (so the agent knows what is in it)
    private fun fmtTime(secs: Int): String = "%d:%02d".format(secs / 60, secs % 60)

    private fun audioMime(ext: String) = when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        "3gp" -> "audio/3gpp"
        else -> "audio/mpeg"
    }

    private fun readAudio(ctx: Context, f: File, name: String, mime: String, srcId: String, p: (String) -> Unit): Out {
        val key = gemKey(ctx)
        if (key.isBlank()) return Out.Fail(Msg.NEED_KEY)
        val models = models(ctx, key)
        if (models.isEmpty()) return Out.Fail(Msg.NEED_KEY)
        val s = Repo.supa(ctx) ?: return Out.Retry(Msg.BUSY)
        val am = if (mime.startsWith("audio/")) mime else audioMime(extOf(name))
        val secs = MediaOut.durationSecs(f)

        var driveId = if (srcId.isNotBlank()) s.getSource(srcId)?.strOr("drive_file_id") ?: "" else ""
        if (driveId.isBlank()) {
            if (!Drive.connected(ctx)) return Out.Fail(Msg.NEED_DRIVE)
            p("حفظ الصوت في Drive الخاص بك…")
            if (Drive.token(ctx) == null) return Out.Retry(Msg.BUSY)
            driveId = Drive.upload(ctx, f, name, am) ?: return Out.Retry(Msg.BUSY)
            if (srcId.isNotBlank()) s.updateSource(srcId, JSONObject().put("drive_file_id", driveId).put("media_mime", am).put("media_secs", secs))
            Sharing.onMediaUploaded(ctx, s, driveId)
        }

        p("الاستماع إلى الصوت وكتابة ملاحظاته…")
        val doc: JSONObject = if (f.length() <= INLINE_MAX) Gem.inlinePart(f.readBytes(), am)
        else Gem.filePart(Gem.upload(key, f, am, name) ?: return Out.Retry(Msg.BUSY), am)
        val single = secs in 1..900
        val seg = if (single) 900 else 600
        val passes = if (secs > 0) (secs + seg - 1) / seg else 12
        val all = StringBuilder()
        var partial = ""
        for (i in 0 until passes.coerceAtMost(30)) {
            val a = i * seg / 60
            val b = (i + 1) * seg / 60
            p(if (single) "الاستماع إلى الصوت وكتابة ملاحظاته…" else "الاستماع إلى الدقائق $a–$b…")
            val scope = if (single) "the WHOLE audio" else "ONLY the part from minute $a to minute $b (minutes counted from the start of the audio)"
            val prompt = "You are preparing complete notes of this audio, covering $scope. " +
                "If it is speech (a lecture, talk, conversation, reading): write in the spoken language and keep every fact, name, number, claim, example and step; do not drop details to be brief. " +
                "Organise with '## [MM:SS] topic' headings (MM:SS = where the topic starts in the audio). " +
                "If it is music, a song or sound effects: write one '## [MM:SS] وصف' section describing the style, mood, language, instruments and general theme; do NOT write out song lyrics. " +
                "Do not comment on your task. " + (if (single) "" else "If the audio has already ended before minute $a, answer only [[END]]; if it ends inside this part, finish with a last line [[END]].")
            val o = Gem.generate(key, models, listOf(doc, Gem.textPart(prompt)), 16000)
            if (!o.ok) {
                if (all.isEmpty()) return fromGemFailure(o)
                partial = "دوّنتُ ملاحظات حتى الدقيقة $a فقط. اضغط تحديث لإعادة المحاولة"
                break
            }
            val ended = o.text.contains("[[END]]")
            val body = o.text.replace("[[END]]", "").trim()
            if (body.length >= 40) all.append(body).append("\n\n")
            if (ended || single) break
        }
        var md = all.toString().trim()
        if (md.length < 80) md = "## [00:00] ملف صوتي\nلا يوجد كلام واضح في هذا الملف الصوتي. اسم الملف: $name."
        val note = (if (secs > 0) "المدة ${fmtTime(secs)}" else "") + (if (partial.isNotBlank()) " — $partial" else "")
        return Out.Ok(md, name.substringBeforeLast('.'), "audio", "gemini", 0, note)
    }

    // ---- YouTube: notes by time windows, so long videos are covered completely
    private fun readYoutube(ctx: Context, url: String, p: (String) -> Unit): Out {
        val key = gemKey(ctx)
        if (key.isBlank()) return Out.Fail(Msg.NEED_KEY)
        val models = models(ctx, key)
        if (models.isEmpty()) return Out.Fail(Msg.NEED_KEY)
        var title = ""
        try {
            val r = Net.get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"), tries = 2)
            if (r != null && r.ok) title = JSONObject(r.text).optString("title")
        } catch (e: Exception) {
        }
        val seg = 900
        var start = 0
        val all = StringBuilder()
        var passes = 0
        while (passes < 14) {
            passes++
            p("قراءة الدقائق ${start / 60}–${(start + seg) / 60} من الفيديو…")
            val part = Gem.filePart(url)
            part.put("video_metadata", JSONObject().put("start_offset", "${start}s").put("end_offset", "${start + seg}s"))
            val prompt = "You are preparing complete study notes from this video, covering ONLY the part from minute ${start / 60} to minute ${(start + seg) / 60}. " +
                "Write in the language spoken in the video. Keep every fact, name, number, claim, example, step and important quote that is said or shown; do not drop details to be brief and do not comment on your task. " +
                "Organise with '## [MM:SS] topic' headings (MM:SS = where the topic starts in the video), each followed by the notes as short paragraphs or lists. " +
                "If the video ends before minute ${(start + seg) / 60}, finish with a last line of exactly [[END]]."
            val o = Gem.generate(key, models, listOf(part, Gem.textPart(prompt)))
            if (!o.ok) {
                if (all.isEmpty()) return fromGemFailure(o, true)
                break // past the end of the video
            }
            val ended = o.text.contains("[[END]]")
            val body = o.text.replace("[[END]]", "").trim()
            if (body.length < 60) break
            if (all.isNotEmpty() && all.contains(body.take(150))) break // the model repeated itself: the video had ended
            all.append(body).append("\n\n")
            if (ended) break
            start += seg
        }
        val md = all.toString().trim()
        if (md.length < 80) return Out.Retry(Msg.VIDEO, 2)
        return Out.Ok(md, title, "video", "gemini")
    }

    // ---- web: direct download first, then three independent fallbacks
    private fun readWeb(ctx: Context, row: JSONObject, p: (String) -> Unit): Out {
        val orig = row.strOr("url")
        val url = rewrite(orig)
        val site = row.strOr("kind") == "site"
        p("جارٍ تحميل الصفحة…")
        val resp = Net.get(url)
        val code = resp?.code ?: 0
        if (resp != null && resp.ok) {
            val r = fromResponse(ctx, resp, url, site, row, p)
            if (r != null) return r
        }
        p("الموقع لم يستجب كما يجب، أجرّب طريقة أخرى…")
        readerFallback(url)?.let { return it }
        archiveFallback(url)?.let { return it }
        val key = gemKey(ctx)
        if (key.isNotBlank()) {
            p("أجرّب القراءة عبر Gemini…")
            geminiWeb(ctx, key, url)?.let { return it }
        }
        return when (code) {
            404, 410 -> Out.Retry(Msg.MISSING, 2)
            401, 403, 451 -> Out.Retry(Msg.BLOCKED, 3)
            else -> Out.Retry(Msg.UNREACHABLE)
        }
    }

    private fun fromResponse(ctx: Context, r: Net.Resp, url: String, site: Boolean, row: JSONObject, p: (String) -> Unit): Out? {
        val ext = extOf(r.url)
        val t = r.type
        val html = t.contains("html")
        if (html || (t.isBlank() && ext in setOf("", "html", "htm", "php", "asp", "aspx"))) {
            val pg = Md.fromHtml(r.bytes, r.url)
            val inner = internalLinks(r.url, pg.links)
            val auto = !site && pg.md.length < 2500 && inner.size >= 12
            if (site || auto) {
                crawl(r, pg, inner, p)?.let { return it }
            }
            if (pg.md.length < 300) return null // probably a page that needs JavaScript: let the fallbacks try
            return Out.Ok(pg.md, pg.title, if (docsLike(url)) "docs" else "article", "direct")
        }
        // a file behind a link: pdf, docx, epub, image, text...
        val tmp = File(ctx.cacheDir, "dl_${System.nanoTime()}")
        return try {
            tmp.writeBytes(r.bytes)
            var name = r.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: "file"
            if (extOf(name).isEmpty()) {
                val guess = when {
                    t.contains("pdf") -> "pdf"
                    t.contains("wordprocessingml") -> "docx"
                    t.contains("presentationml") -> "pptx"
                    t.contains("spreadsheetml") -> "xlsx"
                    t.contains("epub") -> "epub"
                    t.contains("csv") -> "csv"
                    t.startsWith("image/") -> t.substringAfter('/').substringBefore(';')
                    else -> "txt"
                }
                name = "$name.$guess"
            }
            val o = convertFile(ctx, tmp, name, t.substringBefore(';'), p, row.strOr("id"))
            if (o is Out.Ok && o.title.isBlank()) Out.Ok(o.md, name.substringBeforeLast('.'), o.type, "direct", o.pages, o.note) else o
        } finally {
            tmp.delete()
        }
    }

    private fun cleanReader(md: String): String =
        md.replace(Regex("!\\[(?:Image \\d+:?\\s*)?([^\\]]*)]\\([^)]*\\)")) { m -> m.groupValues[1].let { if (it.length >= 4) "[صورة: $it]" else "" } }
            .replace(Regex("\\[([^\\]]+)]\\((?:https?:)?[^)]*\\)"), "$1")

    private fun readerFallback(url: String): Out? = try {
        val r = Net.get("https://r.jina.ai/$url", mapOf("Accept" to "text/plain", "X-Return-Format" to "markdown"), tries = 2)
        if (r != null && r.ok) {
            val txt = r.text
            val title = Regex("(?m)^Title:\\s*(.+)$").find(txt)?.groupValues?.get(1)?.trim() ?: ""
            val body = Md.tidy(cleanReader(txt.substringAfter("Markdown Content:", txt)))
            if (body.length >= 300 && !body.contains("Just a moment", true)) Out.Ok(body, title, if (docsLike(url)) "docs" else "article", "reader") else null
        } else null
    } catch (e: Exception) {
        null
    }

    private fun archiveFallback(url: String): Out? = try {
        val a = Net.get("https://archive.org/wayback/available?url=" + URLEncoder.encode(url, "UTF-8"), tries = 2)
        val snap = if (a != null && a.ok) JSONObject(a.text).optJSONObject("archived_snapshots")?.optJSONObject("closest")?.optString("url") else null
        if (!snap.isNullOrBlank()) {
            val raw = snap.replace("http://", "https://").replace(Regex("/web/(\\d+)/"), "/web/$1id_/")
            val r = Net.get(raw, tries = 2)
            if (r != null && r.ok) {
                val pg = Md.fromHtml(r.bytes, url)
                if (pg.md.length >= 300) Out.Ok(pg.md, pg.title, if (docsLike(url)) "docs" else "article", "archive", 0, "نسخة مؤرشفة من الصفحة") else null
            } else null
        } else null
    } catch (e: Exception) {
        null
    }

    private fun geminiWeb(ctx: Context, key: String, url: String): Out? {
        val models = models(ctx, key)
        if (models.isEmpty()) return null
        val prompt = "Open this page and output its FULL content as faithful Markdown, keeping the page's language and every fact, number and name. " +
            "Use # headings and Markdown tables. Do not summarise and do not comment. Page: $url"
        val o = Gem.generate(key, models, listOf(Gem.textPart(prompt)), 30000, JSONArray().put(JSONObject().put("url_context", JSONObject())), 3)
        if (!o.ok || o.text.trim().length < 300) return null
        return Out.Ok(o.text.trim(), "", if (docsLike(url)) "docs" else "article", "gemini")
    }

    // ---- whole sites / documentation: follows the internal pages, bounded in pages, size and time
    private val SKIP_EXT = setOf("jpg", "jpeg", "png", "gif", "svg", "webp", "zip", "rar", "mp4", "mp3", "css", "js", "ico", "xml", "json", "woff", "woff2", "pdf")
    private val SKIP_PATH = Regex("/(login|signin|signup|register|cart|checkout|account|tag|tags|category|feed|wp-json|wp-admin|search|cdn-cgi)(/|$)", RegexOption.IGNORE_CASE)

    private fun internalLinks(rootUrl: String, links: List<String>): List<String> {
        val u = rootUrl.toHttpUrlOrNull() ?: return emptyList()
        val h = u.host.removePrefix("www.")
        val dir = u.encodedPath.substringBeforeLast('/', "") + "/"
        val seen = LinkedHashSet<String>()
        for (l in links) {
            val x = l.substringBefore('#').toHttpUrlOrNull() ?: continue
            if (x.host.removePrefix("www.") != h) continue
            if (dir != "/" && !x.encodedPath.startsWith(dir)) continue
            if (extOf(x.encodedPath) in SKIP_EXT || SKIP_PATH.containsMatchIn(x.encodedPath)) continue
            val clean = x.newBuilder().fragment(null).build().toString()
            if (clean.trimEnd('/') == rootUrl.substringBefore('#').trimEnd('/')) continue
            seen.add(clean)
        }
        return seen.toList()
    }

    private fun sitemap(rootUrl: String): List<String> {
        val u = rootUrl.toHttpUrlOrNull() ?: return emptyList()
        val base = "${u.scheme}://${u.host}"
        val loc = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>")
        val out = ArrayList<String>()
        try {
            val r = Net.get("$base/sitemap.xml", tries = 1)
            if (r != null && r.ok) {
                val urls = loc.findAll(r.text).map { it.groupValues[1] }.toList()
                val subs = urls.filter { it.endsWith(".xml") }.take(3)
                if (subs.isNotEmpty()) {
                    for (sx in subs) {
                        val rr = Net.get(sx, tries = 1) ?: continue
                        if (rr.ok) loc.findAll(rr.text).forEach { out.add(it.groupValues[1]) }
                    }
                } else out.addAll(urls)
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun crawl(root: Net.Resp, rootPage: Md.Page, inner: List<String>, p: (String) -> Unit): Out? {
        val rootUrl = root.url
        val queue = LinkedHashSet<String>()
        queue.addAll(inner)
        if (queue.size < MAX_SITE_PAGES) queue.addAll(internalLinks(rootUrl, sitemap(rootUrl)))
        val targets = queue.take(MAX_SITE_PAGES)
        if (targets.isEmpty()) return null
        val deadline = System.currentTimeMillis() + 4 * 60_000L
        val results = arrayOfNulls<Md.Page>(targets.size)
        val workers = Executors.newFixedThreadPool(4)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        for ((i, u) in targets.withIndex()) {
            workers.execute {
                try {
                    if (System.currentTimeMillis() < deadline) {
                        val r = Net.get(u, tries = 2)
                        if (r != null && r.ok && (r.type.contains("html") || r.type.isBlank())) {
                            val pg = Md.fromHtml(r.bytes, r.url, 1)
                            if (pg.md.length >= 150) results[i] = pg
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    p("قراءة صفحات الموقع ${done.incrementAndGet()}/${targets.size}…")
                }
            }
        }
        workers.shutdown()
        try { workers.awaitTermination(5, TimeUnit.MINUTES) } catch (e: InterruptedException) { workers.shutdownNow() }
        val sb = StringBuilder()
        val seenHash = HashSet<String>()
        var count = 0
        val first = Md.fromHtml(root.bytes, rootUrl, 1)
        fun add(title: String, url: String, md: String) {
            if (md.isBlank() || sb.length > MAX_CHARS) return
            if (!seenHash.add(Md.hash(md))) return
            val t = title.ifBlank { url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() } ?: url }
            sb.append("# ").append(t).append("\n\n").append(md).append("\n\n")
            count++
        }
        add(first.title.ifBlank { rootPage.title }, rootUrl, first.md)
        for ((i, pg) in results.withIndex()) if (pg != null) add(pg.title, targets[i], pg.md)
        if (count < 2) return null
        val missed = targets.size + 1 - count
        return Out.Ok(
            sb.toString(), rootPage.title, if (docsLike(rootUrl)) "docs" else "site", "direct", 0,
            "قُرئت $count صفحة" + if (missed > 0) " ($missed لم تُقرأ)" else ""
        )
    }
}
