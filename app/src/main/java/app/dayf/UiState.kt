package app.dayf

import android.net.Uri

/** State that must survive the activity being re-created while the system file picker or the consent screen is open. */
object UiState {
    @Volatile var tab = 0
    @Volatile var brainMode = 0
    @Volatile var folder: String? = null
    @Volatile var lastTool = ""
    @Volatile var lastToolAt = 0L

    /** Appended to disconnect messages so the log shows when a drop follows a tool call. */
    fun toolHint(): String {
        val d = (System.currentTimeMillis() - lastToolAt) / 1000
        return if (lastTool.isNotBlank() && d in 0..45) " [بعد استدعاء $lastTool بـ $d ث]" else ""
    }

    @Volatile var fileCb: ((List<Uri>) -> Unit)? = null
}
