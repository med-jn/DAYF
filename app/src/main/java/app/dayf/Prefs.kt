package app.dayf

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("dayf", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) {
        sp(c).edit().putString(k, v).apply()
    }

    /** Write-access secret. Defaults to the one in migration SQL so no manual step is needed. */
    fun admin(c: Context): String = get(c, "adminKey").ifBlank { "CHANGE_ME_SECRET" }

    fun putNow(c: Context, k: String, v: String) {
        sp(c).edit().putString(k, v).commit()
    }
}
