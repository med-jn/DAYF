package app.dayf

object Catalog {
    class GV(val name: String, val ar: String)

    val geminiVoices = listOf(
        GV("Zephyr", "مشرق"), GV("Puck", "مرح"), GV("Charon", "رصين وواضح"), GV("Kore", "حازم"),
        GV("Fenrir", "متحمّس"), GV("Leda", "شبابي"), GV("Orus", "ثابت"), GV("Aoede", "نسيمي"),
        GV("Callirrhoe", "مرتاح"), GV("Autonoe", "مشرق ناعم"), GV("Enceladus", "هامس"), GV("Iapetus", "صافٍ"),
        GV("Umbriel", "هادئ مرتاح"), GV("Algieba", "ناعم"), GV("Despina", "ناعم رقيق"), GV("Erinome", "نقي"),
        GV("Algenib", "أجشّ"), GV("Rasalgethi", "معلوماتي"), GV("Laomedeia", "مرح خفيف"), GV("Achernar", "ليّن"),
        GV("Alnilam", "حازم قوي"), GV("Schedar", "متّزن"), GV("Gacrux", "ناضج"), GV("Pulcherrima", "مباشر"),
        GV("Achird", "ودود"), GV("Zubenelgenubi", "عفوي"), GV("Vindemiatrix", "لطيف"), GV("Sadachbia", "حيوي"),
        GV("Sadaltager", "واسع المعرفة"), GV("Sulafat", "دافئ")
    )

    val dialects = listOf(
        "تونسية", "جزائرية", "مغربية", "ليبية", "مصرية", "لبنانية", "سورية", "فلسطينية",
        "أردنية", "عراقية", "خليجية", "سعودية", "عربية بيضاء"
    )

    val elevenModels = listOf(
        "eleven_flash_v2_5" to "Flash v2.5 (سريع)",
        "eleven_multilingual_v2" to "Multilingual v2 (أنقى)"
    )

    val styles = listOf("دافئ وقريب", "هادئ ورزين", "حماسي وسريع", "مرح وخفيف", "جاد ومباشر", "ساخر بلطف")

    const val SAMPLE = "مرحباً بكم جميعاً، سعيد جداً بوجودي معكم الليلة."
}
