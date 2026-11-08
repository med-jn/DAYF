package app.dayf

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** In-memory copy of agent_config (the database stays the source of truth). */
object Repo {
    @Volatile var raw = JSONObject()
    @Volatile var loaded = false
    @Volatile var error = ""
    @Volatile var liveModel = ""
    private val main = Handler(Looper.getMainLooper())

    fun supa(c: Context): Supa? {
        val u = Prefs.get(c, "supaUrl")
        val k = Prefs.get(c, "supaKey")
        if (u.isBlank() || k.isBlank()) return null
        return Supa(u, k, Prefs.admin(c))
    }

    fun cfg(): Cfg = Cfg(raw)

    fun load(c: Context, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        Thread {
            val s = supa(app)
            var ok = false
            if (s == null) {
                error = "أدخل بيانات Supabase في قسم الإعدادات"
            } else {
                val r = s.config()
                if (r != null) {
                    raw = r
                    loaded = true
                    error = ""
                    Prefs.put(app, "cfgCache", r.toString())
                    ok = true
                } else {
                    error = s.lastError
                    val cache = Prefs.get(app, "cfgCache")
                    if (cache.isNotBlank() && !loaded) {
                        try {
                            raw = JSONObject(cache)
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            main.post { done(ok) }
        }.start()
    }

    fun save(c: Context, patch: JSONObject, done: (Boolean, String) -> Unit) {
        val app = c.applicationContext
        Thread {
            val s = supa(app)
            if (s == null) {
                main.post { done(false, "أدخل بيانات Supabase أولاً") }
                return@Thread
            }
            val res = s.updateConfig(patch)
            if (res.first) {
                val it = patch.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    raw.put(k, patch.get(k))
                }
                Prefs.put(app, "cfgCache", raw.toString())
            }
            main.post { done(res.first, if (res.first) "تم الحفظ" else res.second) }
        }.start()
    }
}
