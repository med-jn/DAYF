package app.dayf

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent log shown inside the app, so failures are never silent. */
object AppLog {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile private var appCtx: Context? = null

    fun init(c: Context) {
        appCtx = c.applicationContext
    }

    @Synchronized
    fun add(msg: String) {
        val c = appCtx ?: return
        val line = fmt.format(Date()) + "  " + msg
        val all = (Prefs.get(c, "applog") + "\n" + line).trim().takeLast(14000)
        Prefs.putNow(c, "applog", all)
    }

    fun text(c: Context): String = Prefs.get(c, "applog")

    fun clear(c: Context) {
        Prefs.putNow(c, "applog", "")
    }

    fun installCrashHandler(c: Context) {
        init(c)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                add(
                    "انهيار في ${t.name}: ${e.javaClass.simpleName}: ${e.message}\n" +
                        e.stackTrace.take(8).joinToString("\n") { "   at $it" }
                )
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
