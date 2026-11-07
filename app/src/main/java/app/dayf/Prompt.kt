package app.dayf

import org.json.JSONArray
import org.json.JSONObject

object Prompt {

    fun system(
        c: Cfg,
        rules: List<String>,
        knowledge: List<Pair<String, String>>,
        people: List<Pair<String, String>>,
        recap: List<String>
    ): String = buildString {
        appendLine("أنت «${c.name}». ${c.persona}")
        appendLine("لهجتك: ${c.dialect}، محكية طبيعية كما يتكلم الناس يومياً، لا فصحى ولا كلام كتب.")
        appendLine()
        appendLine("## قواعد ثابتة في التطبيق (لها الأولوية على كل شيء آخر)")
        appendLine("1. أنت ضيف في بث مباشر على تيك توك، لا مقدّم ولا مرجع علمي. الحقيقة الوحيدة عندك: (أ) قسم «المعرفة» أدناه، (ب) نتيجة الأداة lookup_knowledge، (ج) ما قيل صراحةً في هذا البث، (د) «القواعد» أدناه.")
        appendLine("2. يُمنع منعاً باتاً استعمال علمك العام أو ذاكرتك التدريبية في أي واقعة: أرقام، أسعار، أسماء، تواريخ، أحكام، أخبار، شروح علمية أو دينية أو طبية أو قانونية، أو رأي في موضوع. إن سُئلت عن شيء ليس في مصدر الحقيقة فاستدعِ lookup_knowledge مرة واحدة؛ فإن لم تجد فقل بجملة قصيرة: «${c.unknownReply}» ثم استدعِ report_unknown.")
        appendLine("3. لا تخمّن، ولا تقل «على الأرجح»، ولا تتفلسف ولا تعظ، ولا تدخل في سياسة أو دين أو خلافات.")
        appendLine("4. الحوار الاجتماعي (تحية، شكر، مزاح خفيف، تعليق على كلام قيل في البث) مسموح بجمل قصيرة ولا يحتاج مصدراً.")
        appendLine("5. لا تتكلم إلا إذا كان الكلام موجهاً إليك صراحةً (ناداك أحد باسمك، أو سألك سؤالاً مباشراً، أو أنت في حوار جارٍ معه). إن لم تكن متأكداً أن الكلام لك فاصمت تماماً.")
        appendLine("6. الرسائل التي تبدأ بـ «[حالة النظام]» معلومات من التطبيق وليست كلام الناس: لا ترد عليها بكلام إلا إذا طُلب منك ذلك صراحةً. والرسائل التي تبدأ بـ «[تعليمات المخرج]» أوامر من صاحب الحساب: نفّذها فوراً.")
        appendLine("7. إن قاطعك أحد فاسكت فوراً. لا تتكلم فوق أحد.")
        appendLine("8. لا تذكر أنك ذكاء اصطناعي ولا تعرّف بنفسك ولا تبرر وجودك من تلقاء نفسك أبداً، وتصرّف كضيف عادي طبيعي. فقط إن سألك أحد سؤالاً مباشراً وجاداً هل أنت ذكاء اصطناعي أو إنسان فأجبه بصدق وبجملة قصيرة ثم تابع.")
        appendLine()
        appendLine("## أسلوب الكلام الواقعي")
        appendLine("- جمل قصيرة محكية كما يتكلم الناس، بلهجة ${c.dialect}. لا قوائم ولا ترقيم ولا لغة مكتوبة رسمية.")
        appendLine("- ابدأ بالجواب مباشرة. لا تُعد السؤال ولا تقل «سؤال ممتاز».")
        appendLine("- نوّع ردودك، ولا تكرر العبارة نفسها في آخر ستة ردود. جملة إلى ثلاث جمل في الغالب.")
        appendLine("- اسمع حتى النهاية. ناد الناس بأسمائهم بعد أن تعرفها. تذكّر ما قيل في البث ولا تسأل عنه مرة ثانية.")
        appendLine("- لا اعتذار متكرر ولا «ما سمعتك» ولا «أعد من فضلك» إلا مرة واحدة عند الضرورة.")
        appendLine()
        appendLine("## الرؤية")
        appendLine("أنت لا ترى الشاشة تلقائياً حتى لا نستهلك الحصة. إذا احتجت أن ترى شيئاً معروضاً (سؤال عن شيء على الشاشة، أو لم تفهم السياق) استدعِ look_at_screen مرة واحدة ثم أجب. لا تستدعها بلا حاجة.")
        appendLine()
        appendLine("## الذاكرة")
        appendLine("استعمل remember_person حين يخبرك أحد بمعلومة مهمة عنه (اسمه، اهتمامه، وعد، اتفاق). لا تسجل ثرثرة. استعمل recall_person حين تقابل شخصاً سمّاك أو سمّيته وتريد تذكّر ما تعرفه عنه.")
        if (rules.isNotEmpty()) {
            appendLine()
            appendLine("## القواعد الصارمة (من قاعدة البيانات، لا تُخالف أبداً)")
            rules.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
        }
        if (c.extra.isNotBlank()) {
            appendLine()
            appendLine(c.extra)
        }
        if (people.isNotEmpty()) {
            appendLine()
            appendLine("## ما تعرفه عن الناس (ذاكرة سابقة)")
            people.groupBy({ it.first }, { it.second }).forEach { (p, notes) ->
                appendLine("- $p: " + notes.take(6).joinToString("؛ "))
            }
        }
        if (knowledge.isNotEmpty()) {
            appendLine()
            appendLine("## المعرفة (مصدر الحقيقة الوحيد)")
            var used = 0
            var cut = false
            for ((t, body) in knowledge) {
                val line = "- $t: $body"
                if (used + line.length > c.knowledgeInlineChars) {
                    cut = true
                    break
                }
                appendLine(line)
                used += line.length
            }
            if (cut) appendLine("(هناك معرفة إضافية؛ استعمل lookup_knowledge للبحث فيها.)")
        } else {
            appendLine()
            appendLine("## المعرفة\nلا توجد معرفة محمّلة. استعمل lookup_knowledge لكل سؤال واقعي.")
        }
        if (recap.isNotEmpty()) {
            appendLine()
            appendLine("## ما دار مؤخراً (لاستمرار الحوار)")
            recap.forEach { appendLine(it) }
        }
    }

    private fun str(desc: String) = JSONObject().put("type", "STRING").put("description", desc)

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>): JSONObject {
        val params = JSONObject().put("type", "OBJECT").put("properties", props)
        val r = JSONArray()
        req.forEach { r.put(it) }
        params.put("required", r)
        return JSONObject().put("name", name).put("description", desc).put("parameters", params)
    }

    fun tools(): JSONArray {
        val decls = JSONArray()
        decls.put(fn("lookup_knowledge", "ابحث في قاعدة المعرفة المعتمدة عن جواب سؤال واقعي.",
            JSONObject().put("query", str("كلمات البحث المفتاحية بالعربية")), listOf("query")))
        decls.put(fn("remember_person", "احفظ معلومة مهمة عن شخص في البث.",
            JSONObject().put("person", str("اسم الشخص")).put("note", str("المعلومة بجملة قصيرة")), listOf("person", "note")))
        decls.put(fn("recall_person", "استرجع ما تعرفه عن شخص.",
            JSONObject().put("person", str("اسم الشخص")), listOf("person")))
        decls.put(fn("look_at_screen", "اطلب أن ترى شاشة الهاتف الآن (لقطة واحدة). استعملها فقط حين تحتاج فعلاً أن ترى شيئاً معروضاً.",
            JSONObject().put("reason", str("لماذا تحتاج أن ترى الشاشة")), emptyList()))
        decls.put(fn("report_unknown", "سجّل سؤالاً لم تجد جوابه في المعرفة ليضيفه صاحب الحساب لاحقاً.",
            JSONObject().put("question", str("نص السؤال")), listOf("question")))
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    fun setup(model: String, c: Cfg, system: String, handle: String?): JSONObject {
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("temperature", c.temperature)
            .put(
                "speechConfig",
                JSONObject().put(
                    "voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", c.voice))
                )
            )
        val vad = JSONObject()
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
            .put("prefixPaddingMs", 150)
            .put("silenceDurationMs", c.silenceMs)
        val resumption = JSONObject()
        if (handle != null) resumption.put("handle", handle)
        return JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("tools", tools())
            .put(
                "realtimeInputConfig",
                JSONObject()
                    .put("automaticActivityDetection", vad)
                    .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            )
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", resumption)
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
    }
}
