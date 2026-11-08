package app.dayf

object Ar {
    private val diac = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private val alef = Regex("[إأآٱ]")
    private val junk = Regex("[^\\p{L}\\p{N}\\s]")
    private val spaces = Regex("\\s+")

    fun norm(s: String): String = s.lowercase()
        .replace(diac, "")
        .replace(alef, "ا")
        .replace('ى', 'ي')
        .replace('ة', 'ه')
        .replace('ؤ', 'و')
        .replace('ئ', 'ي')
        .replace(junk, " ")
        .replace(spaces, " ")
        .trim()

    /** names must already be normalized with norm(). */
    fun mentions(text: String, names: List<String>): Boolean {
        val t = " " + norm(text) + " "
        for (n in names) {
            if (n.isBlank()) continue
            if (t.contains(" $n ")) return true
            if (n.length >= 4 && t.contains(n)) return true
        }
        return false
    }

    private val stops = listOf(
        "اسكت", "اسكتي", "وقف", "وقفي", "كفى", "بس", "خلاص", "لحظة", "استنى", "استني", "اصبر", "ثانية", "stop", "wait"
    ).map { norm(it) }

    fun hasStopWord(text: String): Boolean {
        val t = " " + norm(text) + " "
        return stops.any { t.contains(" $it ") }
    }
}
