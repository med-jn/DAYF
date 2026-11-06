package app.dayf

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class Keywords(
    val guest: List<String>,
    val requested: List<String>,
    val invited: List<String>,
    val viewer: List<String>,
    val micMuted: List<String>,
    val micOpen: List<String>
) {
    fun isEmpty() = guest.isEmpty() && requested.isEmpty() && invited.isEmpty() &&
        viewer.isEmpty() && micMuted.isEmpty() && micOpen.isEmpty()

    companion object {
        fun from(j: JSONObject?): Keywords {
            fun l(k: String): List<String> {
                val a = j?.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it).lowercase() }.filter { it.isNotBlank() }
            }
            return Keywords(l("guest"), l("requested"), l("invited"), l("viewer"), l("mic_muted"), l("mic_open"))
        }
    }
}

/** Reads TikTok's on-screen texts to detect the guest state. Never clicks or types anything. */
class TikTokWatcher : AccessibilityService() {

    companion object {
        @Volatile var instance: TikTokWatcher? = null
        @Volatile var keywords: Keywords = Keywords.from(null)

        fun dump(): String {
            val i = instance ?: return "خدمة الإتاحة غير مفعّلة"
            return i.dumpTree()
        }
    }

    private var last = 0L

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val kw = keywords
        if (kw.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - last < 600) return
        last = now
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>()
        collect(root, texts, 0)
        val all = texts.map { it.lowercase() }
        fun has(l: List<String>): Boolean = l.any { k -> all.any { it.contains(k) } }
        val g = when {
            has(kw.invited) -> GuestState.INVITED
            has(kw.requested) -> GuestState.REQUESTED
            has(kw.guest) -> GuestState.GUEST
            has(kw.viewer) -> GuestState.VIEWER
            else -> GuestState.UNKNOWN
        }
        val m = when {
            has(kw.micMuted) -> MicState.MUTED
            has(kw.micOpen) -> MicState.OPEN
            else -> MicState.UNKNOWN
        }
        StateBus.a11yGuest = g
        StateBus.a11yMic = m
        StateBus.recompute()
    }

    private fun collect(n: AccessibilityNodeInfo, out: ArrayList<String>, depth: Int) {
        if (depth > 40 || out.size > 1500) return
        n.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        n.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, out, depth + 1)
        }
    }

    private fun dumpTree(): String {
        val root = rootInActiveWindow ?: return "لا توجد نافذة نشطة (افتح تيك توك أولاً)"
        val sb = StringBuilder()
        sb.append("pkg=").append(root.packageName).append('\n')
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 40 || sb.length > 30000) return
            val t = n.text?.toString() ?: ""
            val c = n.contentDescription?.toString() ?: ""
            val id = n.viewIdResourceName?.substringAfter('/') ?: ""
            if (t.isNotBlank() || c.isNotBlank() || id.isNotBlank()) {
                sb.append(" ".repeat(minOf(d, 20))).append(n.className?.toString()?.substringAfterLast('.') ?: "")
                    .append(" | t=").append(t).append(" | d=").append(c).append(" | id=").append(id).append('\n')
            }
            for (i in 0 until n.childCount) {
                val ch = n.getChild(i) ?: continue
                walk(ch, d + 1)
            }
        }
        walk(root, 0)
        return sb.toString()
    }
}
