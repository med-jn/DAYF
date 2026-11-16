package app.dayf

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Receives com.tikguest://share/CODE, keeps the code, and opens the app, which then offers to add the persona. */
class ShareLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.data?.lastPathSegment?.trim()?.uppercase() ?: ""
        if (code.isNotBlank()) Prefs.putNow(this, "pending_share", code)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
