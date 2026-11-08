package app.dayf

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class GuestState(val ar: String) {
    UNKNOWN("غير معروف"),
    NOT_LIVE("خارج البث"),
    VIEWER("مشاهد"),
    REQUESTED("الطلب بانتظار المضيف"),
    INVITED("مدعو ولم يُقبل"),
    GUEST("ضيف"),
    REMOVED("أُنزلت من البث")
}

enum class MicState(val ar: String) { UNKNOWN("؟"), OPEN("مفتوح"), MUTED("مغلق") }

enum class SpeakMode(val ar: String) { ON_CALL("عند النداء"), AUTO("تلقائي"), SILENT("صامت") }

object StateBus {
    @Volatile var a11yGuest = GuestState.UNKNOWN
    @Volatile var a11yMic = MicState.UNKNOWN
    @Volatile var visGuest = GuestState.UNKNOWN
    @Volatile var visMic = MicState.UNKNOWN
    @Volatile var manualGuest: GuestState? = null
    @Volatile var manualMic: MicState? = null

    @Volatile var guestEff = GuestState.UNKNOWN
    @Volatile var micEff = MicState.UNKNOWN

    @Volatile var mode = SpeakMode.ON_CALL
    @Volatile var allowUnknown = false
    @Volatile var addressedUntil = 0L
    @Volatile var forceUntil = 0L
    @Volatile var silenceUntil = 0L
    @Volatile var speaking = false
    @Volatile var captureLevel = 0
    @Volatile var lastBlock = ""

    private var removed = false
    private var prevDetected = GuestState.UNKNOWN

    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun changed() {
        main.post { listeners.forEach { it() } }
    }

    @Synchronized
    fun recompute() {
        val det = if (a11yGuest != GuestState.UNKNOWN) a11yGuest else visGuest
        if (prevDetected == GuestState.GUEST && (det == GuestState.VIEWER || det == GuestState.NOT_LIVE)) removed = true
        if (det == GuestState.GUEST || det == GuestState.REQUESTED || det == GuestState.INVITED) removed = false
        prevDetected = det

        val m = manualGuest
        val g = when {
            m != null -> m
            removed -> GuestState.REMOVED
            else -> det
        }
        val mic = manualMic ?: (if (a11yMic != MicState.UNKNOWN) a11yMic else visMic)
        if (g != guestEff || mic != micEff) {
            guestEff = g
            micEff = mic
            changed()
        }
    }

    /** null = allowed to speak right now. mid=true while a sentence is in progress: transient unknowns never cut it. */
    fun hardBlock(now: Long, mid: Boolean = false): String? {
        if (mode == SpeakMode.SILENT) return "وضع الصمت"
        if (now < silenceUntil) return "صمت مؤقت"
        val g = guestEff
        if (g == GuestState.REMOVED || g == GuestState.VIEWER || g == GuestState.NOT_LIVE ||
            g == GuestState.REQUESTED || g == GuestState.INVITED
        ) return "لست ضيفاً (${g.ar})"
        if (g == GuestState.UNKNOWN && !allowUnknown && !mid) return "لست ضيفاً (${g.ar})"
        if (micEff == MicState.MUTED) return "المايك مغلق"
        if (micEff == MicState.UNKNOWN && !allowUnknown && manualGuest == null && !mid) return "حالة المايك مجهولة"
        return null
    }

    fun canStartTurn(now: Long): String? {
        val h = hardBlock(now)
        if (h != null) return h
        if (mode == SpeakMode.ON_CALL && now > addressedUntil && now > forceUntil) return "الكلام ليس موجهاً إليك"
        return null
    }

    fun shouldListen(): Boolean {
        val g = guestEff
        return g == GuestState.GUEST || g == GuestState.REQUESTED || g == GuestState.INVITED ||
            g == GuestState.VIEWER || (g == GuestState.UNKNOWN && allowUnknown)
    }
}
