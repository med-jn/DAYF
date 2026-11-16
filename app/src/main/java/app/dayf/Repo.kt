package app.dayf

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** In-memory copy of the active persona (the database stays the source of truth). */
object Repo {
    @Volatile var raw = JSONObject()
    @Volatile var personaId = ""
    @Volatile var personas = JSONArray()
    @Volatile var loaded = false

    /** owner | edit | use | "" — what this account may do with the active persona. */
    @Volatile var role = "owner"

    /** True when the active persona belongs to this account. */
    fun isOwner(c: Context): Boolean {
        val o = raw.optString("user_id")
        return o.isBlank() || o == Auth.userId(c)
    }

    fun canEdit(c: Context): Boolean = isOwner(c) || role == "edit"

    const val READ_ONLY = "صلاحيتك في هذه الشخصية: استخدام فقط. يتولى المالك تعديلها."
    @Volatile var error = ""
    @Volatile var liveModel = ""
    @Volatile var textModel = ""
    private val main = Handler(Looper.getMainLooper())

    fun supa(c: Context): Supa? = if (Auth.isSignedIn(c)) Supa(c.applicationContext) else null

    fun cfg(): Cfg = Cfg(raw)

    fun reset() {
        raw = JSONObject()
        personaId = ""
        personas = JSONArray()
        loaded = false
    }

    fun load(c: Context, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        Thread {
            val s = supa(app)
            var ok = false
            if (s == null) {
                error = "سجّل الدخول أولاً"
            } else {
                val pid = s.ensureProfile()
                if (pid == null) {
                    error = s.lastError
                } else {
                    val p = s.persona(pid)
                    if (p != null) {
                        raw = p
                        personaId = pid
                        role = if (isOwner(app)) "owner" else s.myRole(pid)
                        personas = s.personas() ?: JSONArray()
                        loaded = true
                        error = ""
                        ok = true
                        Secrets.restore(app, s)
                        Ingest.sweep(app)
                    } else error = s.lastError
                }
            }
            main.post { done(ok) }
        }.start()
    }

    fun save(c: Context, patch: JSONObject, done: (Boolean, String) -> Unit) {
        if (!canEdit(c)) {
            done(false, READ_ONLY)
            return
        }
        val app = c.applicationContext
        Thread {
            val s = supa(app)
            if (s == null || personaId.isBlank()) {
                main.post { done(false, "سجّل الدخول أولاً") }
                return@Thread
            }
            val res = s.updatePersona(personaId, patch)
            if (res.first) {
                val it = patch.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    raw.put(k, patch.get(k))
                }
                if (patch.has("title") || patch.has("name")) personas = s.personas() ?: personas
            }
            main.post { done(res.first, if (res.first) "تم الحفظ" else res.second) }
        }.start()
    }

    fun switchTo(c: Context, id: String, done: (Boolean, String) -> Unit) {
        val app = c.applicationContext
        Thread {
            val s = supa(app)
            if (s == null) {
                main.post { done(false, "سجّل الدخول أولاً") }
                return@Thread
            }
            val r = s.setActive(id)
            main.post {
                if (r.first) load(app) { ok -> done(ok, if (ok) "تم" else error) } else done(false, r.second)
            }
        }.start()
    }

    /** Creates a persona (optionally a copy of the current one) and makes it active. */
    fun create(c: Context, title: String, copyCurrent: Boolean, done: (Boolean, String) -> Unit) {
        val app = c.applicationContext
        Thread {
            val s = supa(app)
            if (s == null) {
                main.post { done(false, "سجّل الدخول أولاً") }
                return@Thread
            }
            val row = JSONObject()
            if (copyCurrent) {
                val it = raw.keys()
                val skip = setOf("id", "user_id", "created_at", "updated_at", "title")
                while (it.hasNext()) {
                    val k = it.next()
                    if (k !in skip) row.put(k, raw.get(k))
                }
            }
            row.put("title", title)
            val id = s.createPersona(row)
            if (id == null) {
                val e = s.lastError
                main.post { done(false, e) }
                return@Thread
            }
            if (copyCurrent) {
                // copy rules and knowledge too
                s.listRules(personaId)?.let { a ->
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        s.saveRow("rules", null, JSONObject().put("persona_id", id).put("text", o.strOr("text")).put("priority", o.intOr("priority", 100)).put("active", o.boolOr("active", true)))
                    }
                }
                s.listKnowledge(personaId)?.let { a ->
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        s.saveRow("knowledge", null, JSONObject().put("persona_id", id).put("topic", o.strOr("topic")).put("content", o.strOr("content")).put("keywords", o.strOr("keywords")).put("active", o.boolOr("active", true)))
                    }
                }
            }
            val r = s.setActive(id)
            main.post {
                if (r.first) load(app) { ok -> done(ok, if (ok) "تم" else error) } else done(false, r.second)
            }
        }.start()
    }

    fun delete(c: Context, id: String, done: (Boolean, String) -> Unit) {
        val app = c.applicationContext
        Thread {
            val s = supa(app)
            if (s == null) {
                main.post { done(false, "سجّل الدخول أولاً") }
                return@Thread
            }
            val r = s.deletePersona(id)
            main.post {
                if (r.first) load(app) { ok -> done(ok, if (ok) "تم" else error) } else done(false, r.second)
            }
        }.start()
    }
}
