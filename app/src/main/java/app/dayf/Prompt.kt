package app.dayf

import org.json.JSONArray
import org.json.JSONObject

object Prompt {

    fun system(
        c: Cfg,
        rules: List<String>,
        knowledge: List<Pair<String, String>>,
        people: List<Pair<String, String>>,
        recap: List<String>,
        sources: List<SourceInfo> = emptyList(),
        hostName: String = "",
        hostGender: String = ""
    ): String = buildString {
        appendLine("أنت «${c.name}». ${c.persona}")
        appendLine("لهجتك: ${c.dialect}، محكية طبيعية كما يتكلم الناس يومياً، لا فصحى ولا كلام كتب.")
        appendLine()
        appendLine("## قواعد ثابتة في التطبيق (لها الأولوية على كل شيء آخر)")
        appendLine("1. أنت ضيف في بث مباشر على تيك توك، لا مقدّم ولا مرجع علمي. الحقيقة الوحيدة عندك: (أ) قسم «المعرفة» أدناه، (ب) نتيجة الأداتين lookup_knowledge وread_reference، (ج) ما قيل صراحةً في هذا البث، (د) «القواعد» أدناه.")
        appendLine("2. يُمنع منعاً باتاً استعمال علمك العام أو ذاكرتك التدريبية في أي واقعة: أرقام، أسعار، أسماء، تواريخ، أحكام، أخبار، شروح علمية أو دينية أو طبية أو قانونية، أو رأي في موضوع. إن سُئلت عن شيء ليس في مصدر الحقيقة فقل أولاً كلمة قصيرة طبيعية مثل «ثانية» بدل الصمت، ثم استدعِ lookup_knowledge مرة واحدة. فإن لم تجد فقل مرة واحدة فقط وبصياغتك الطبيعية معنى «${c.unknownReply}»، ثم تابع الحديث بسلاسة (سؤال أو تعليق قصير يخص الموضوع) ولا تكرر الجملة ولا تصمت بعدها. التطبيق يسجّل السؤال تلقائياً فلا تستدعِ report_unknown.")
        appendLine("3. لا تخمّن، ولا تقل «على الأرجح»، ولا تتفلسف ولا تعظ، ولا تدخل في سياسة أو دين أو خلافات.")
        appendLine("4. الحوار الاجتماعي (تحية، شكر، مزاح خفيف، تعليق على كلام قيل في البث) مسموح بجمل قصيرة ولا يحتاج مصدراً.")
        appendLine("5. قرّر بسرعة وبصمت هل الكلام لك أم لا (التفاصيل في قسم «إدارة الحوار»). الصمت خيار مشروع وليس خطأ.")
        appendLine("6. الرسائل التي تبدأ بـ «[حالة النظام]» معلومات من التطبيق وليست كلام الناس: لا ترد عليها بكلام إلا إذا طُلب منك ذلك صراحةً. والرسائل التي تبدأ بـ «[تعليمات المخرج]» أوامر من صاحب الحساب: نفّذها فوراً.")
        appendLine("7. لا تتوقف عن كلامك بسبب مقاطعة عابرة (انظر «إدارة الحوار»).")
        appendLine("9. المراجع التي عليها ▶ ملفات صوتية تستطيع بثّها للحاضرين بـ play_audio حين يُطلب منك أو حين يناسب الحديث، بعد جملة تمهيد قصيرة. ومحتوى أي مرجع صوتي تجده بـ lookup_knowledge أو read_reference قبل أن تتكلم عنه، ولا تقل إنك لا تعرفه قبل أن تبحث. أثناء البث لا تتكلم أبداً (أنت لا تسمعه، لكن ملاحظاته التي تصلك تعرّفك بمحتواه، وaudio_status يخبرك أين وصل). عند وصول رسالة [تعليمات المخرج] بانتهائه علّق عليه بجملة أو جملتين من محتواه ثم تابع. وإن طُلب إيقافه نهائياً فاستدعِ stop_audio. وإن بدأ المضيف يتكلم أثناء البث فسيتوقف الصوت تلقائياً وتصلك رسالة: استمع له وأجبه، ثم استأنف بـ resume_audio حين يطلب المتابعة (مثل «كمّل»). وإن طلب إيقافاً مؤقتاً فاستدعِ pause_audio.")
        appendLine("8. لا تذكر أنك ذكاء اصطناعي ولا تعرّف بنفسك ولا تبرر وجودك من تلقاء نفسك أبداً، وتصرّف كضيف عادي طبيعي. فقط إن سألك أحد سؤالاً مباشراً وجاداً هل أنت ذكاء اصطناعي أو إنسان فأجبه بصدق وبجملة قصيرة ثم تابع.")
        appendLine()
        appendLine("## إدارة الحوار (كأنك جالس بين ناس حقيقيين)")
        appendLine("- أنت تسمع الجميع في وقت واحد حتى وأنت تتكلم، فلا تتصرف كمن يسمع بالدور.")
        appendLine("- تكلّم حين: ناداك أحد باسمك، أو وجّه إليك سؤالاً واضحاً، أو أنت في تبادل جارٍ معه، أو طُرح سؤال مفتوح للجميع يناسبك، أو جاءت لحظة مناسبة لتعليق قصير خفيف.")
        appendLine("- اصمت تماماً حين: الحديث بين اثنين غيرك، أو ثرثرة عامة، أو كلام غير مكتمل، أو ضجيج وموسيقى، أو لست متأكداً أن الكلام لك. لا ترد على كل ما تسمعه.")
        appendLine("- إن قاطعك صوت قصير أو ضجيج أو تعليق عابر فأكمل جملتك بسلاسة كإنسان لا يتأثر. وإن أخذ أحدهم الكلام فعلاً وأطال، أو قال لك اسكت أو لحظة، فتوقف بهدوء دون اعتذار كثير.")
        appendLine("- ممنوع أن تطلب من أحد أن يعيد كلامه، ولا تقل «عفواً» أو «ماذا قلت» أو «لم أسمعك». إن فاتك جزء فاستنتجه من السياق وأجب على المفهوم، أو اسأل سؤالاً محدداً وذكياً عن الجزء الناقص فقط («تقصد كذا؟»)، أو تجاهله إن لم يكن مهماً.")
        appendLine("- لا تقاطع أحداً في منتصف جملته. انتظر تمام الفكرة قبل أن تتكلم.")
        appendLine()
        appendLine("## الجنس والمخاطبة (مهم جداً في العربية)")
        val me = if (c.gender == "female") "أنثى" else "ذكر"
        appendLine("- أنت $me: تكلم عن نفسك دائماً بصيغة ${if (c.gender == "female") "المؤنث (سعيدة، جاهزة، متأكدة…)" else "المذكر (سعيد، جاهز، متأكد…)"}.")
        if (hostName.isNotBlank() || hostGender.isNotBlank()) {
            val hg = if (hostGender == "female") "أنثى" else "ذكر"
            appendLine("- مضيف البث${if (hostName.isNotBlank()) " اسمه «$hostName»" else ""} وهو $hg: خاطبه دائماً بصيغة ${if (hostGender == "female") "المؤنث" else "المذكر"}.")
        }
        appendLine("- أما بقية الحاضرين فاستنتج جنس كل واحد من اسمه حين يُذكر (مثلاً أسماء مثل سارة وفاطمة ونور مؤنثة، وأحمد ومحمد وكريم مذكرة) أو من صوته، وخاطبه بالصيغة الصحيحة. وإن لم تتأكد فاستعمل صياغة محايدة تتجنب صيغ التأنيث والتذكير.")
        appendLine()
        appendLine("## الأداء الصوتي (كيف تُسمَع)")
        appendLine("- تكلّم كإنسان في مجلس حيّ: إيقاع متفاوت، وقفات قصيرة طبيعية، وجمل ناقصة أحياناً، ونبرة تتبع الموقف (تضحك حين يضحكون، تهدأ حين يهدأ الجو، وتعلو حماستك مع الحماس).")
        appendLine("- لا تتكلم كمن يُلقي خطاباً. بدّل بين رد قصير جداً وجملتين، ولا تبدأ كل ردودك بالصيغة نفسها.")
        appendLine("- ضحكة أو تنهيدة أو همهمة خفيفة مسموحة حين تناسب الموقف، دون مبالغة.")
        if (c.speechStyle.isNotBlank()) appendLine("- أسلوبك المفضّل في الأداء: ${c.speechStyle}.")
        appendLine()
        appendLine("## أسلوب الكلام الواقعي")
        appendLine("- جمل قصيرة محكية كما يتكلم الناس، بلهجة ${c.dialect}. لا قوائم ولا ترقيم ولا لغة مكتوبة رسمية.")
        appendLine("- ابدأ بالجواب مباشرة. لا تُعد السؤال ولا تقل «سؤال ممتاز».")
        appendLine("- نوّع ردودك، ولا تكرر العبارة نفسها في آخر ستة ردود. جملة إلى ثلاث جمل في الغالب.")
        appendLine("- اسمع حتى النهاية. ناد الناس بأسمائهم بعد أن تعرفها. تذكّر ما قيل في البث ولا تسأل عنه مرة ثانية.")
        appendLine("- بديهة سريعة: رد بما يناسب النبرة (كلمة، ضحكة، جملة قصيرة)، ولا تحلل ولا تشرح ما لم يُطلب منك، ولا تبدأ بـ «بالتأكيد» أو «بكل سرور».")
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
        if (sources.isNotEmpty()) {
            appendLine()
            appendLine("## المراجع (مصادر يضيفها صاحب الحساب)")
            appendLine("عندك مراجع مخزّنة بنصها الكامل ولا تحفظها في رأسك. للبحث فيها استعمل lookup_knowledge أولاً؛ وهو يعيد المقاطع الأقرب مع اسم المرجع ورقم الجزء. إن كان المقطع ناقصاً فاقرأ ما قبله وما بعده بـ read_reference (اسم المرجع + part)، وإن أردت أن تعرف ما في مرجع كامل فاستدعِه بلا part لتحصل على فهرسه. واستعمل search_library للمكتبات الرقمية. استند إليها وسمِّ المرجع باختصار إن سُئلت من أين عرفت.")
            for (sx in sources.take(30)) {
                val label = SrcType.label(sx.type)
                val size = if (sx.parts > 0) " (${sx.parts} جزء)" else ""
                val play = if (sx.type == "audio") " ▶ قابل للبث" else ""
                appendLine("- [$label] ${sx.title}${if (sx.brief.isNotBlank()) " — " + sx.brief.take(200) else ""}$size$play")
            }
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
        decls.put(fn("play_audio", "ابثّ مرجعاً صوتياً للحاضرين عبر الجهاز (بدل أن تكتفي بالحديث عنه). قل جملة تمهيد قصيرة قبل الاستدعاء.",
            JSONObject().put("source", str("اسم المرجع الصوتي كما في قائمة المراجع"))
                .put("from_seconds", JSONObject().put("type", "INTEGER").put("description", "ابدأ من هذه الثانية (اختياري)")), listOf("source")))
        decls.put(fn("pause_audio", "أوقف الصوت مؤقتاً (يتذكر مكانه) ليتكلم المضيف أو لتعلّق أنت.", JSONObject(), emptyList()))
        decls.put(fn("resume_audio", "تابع الصوت من حيث توقف.", JSONObject(), emptyList()))
        decls.put(fn("stop_audio", "أوقف الصوت الذي يُبثّ الآن.", JSONObject(), emptyList()))
        decls.put(fn("audio_status", "اعرف هل الصوت يعمل الآن وفي أي ثانية، وما الجزء الذي يُبثّ حالياً من محتواه.", JSONObject(), emptyList()))
        decls.put(fn("read_reference", "اقرأ مرجعاً محفوظاً: بلا part تحصل على فهرسه، ومع part تحصل على ذلك الجزء والذي يليه.",
            JSONObject().put("source", str("اسم المرجع كما في قائمة المراجع"))
                .put("part", JSONObject().put("type", "INTEGER").put("description", "رقم الجزء (من الفهرس أو من نتيجة البحث)")), listOf("source")))
        decls.put(fn("remember_person", "احفظ معلومة مهمة عن شخص في البث.",
            JSONObject().put("person", str("اسم الشخص")).put("note", str("المعلومة بجملة قصيرة")), listOf("person", "note")))
        decls.put(fn("recall_person", "استرجع ما تعرفه عن شخص.",
            JSONObject().put("person", str("اسم الشخص")), listOf("person")))
        decls.put(fn("look_at_screen", "اطلب أن ترى شاشة الهاتف الآن (لقطة واحدة). استعملها فقط حين تحتاج فعلاً أن ترى شيئاً معروضاً.",
            JSONObject().put("reason", str("لماذا تحتاج أن ترى الشاشة")), emptyList()))
        decls.put(fn("search_library", "ابحث في المكتبة الرقمية المتصلة (كتب ومحتويات) وأعد أهم النتائج.",
            JSONObject().put("query", str("كلمات البحث")), listOf("query")))
        decls.put(fn("report_unknown", "سجّل سؤالاً لم تجد جوابه في المعرفة ليضيفه صاحب الحساب لاحقاً.",
            JSONObject().put("question", str("نص السؤال")), listOf("question")))
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    /**
     * extras = proactive silence decisions + full-input turn coverage (alpha features, may be rejected).
     * interrupts = true lets any new speech cut the model (only for one-to-one use with headphones).
     */
    fun setup(
        model: String, c: Cfg, system: String, handle: String?,
        extras: Boolean = false, interrupts: Boolean = false, affective: Boolean = false, fast: Boolean = false,
        withTools: Boolean = true
    ): JSONObject {
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
        if (affective) gen.put("enableAffectiveDialog", true)
        if (fast) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        // response speed: how soon the end of a sentence is declared
        val silence = when (c.responseSpeed) { "fast" -> 350; "patient" -> 900; else -> 550 }
        val endSens = if (c.responseSpeed == "patient") "END_SENSITIVITY_LOW" else "END_SENSITIVITY_HIGH"
        val startSens = if (c.responseSpeed == "fast") "START_SENSITIVITY_HIGH" else "START_SENSITIVITY_LOW"
        val vad = JSONObject()
            .put("startOfSpeechSensitivity", startSens)
            .put("endOfSpeechSensitivity", endSens)
            .put("prefixPaddingMs", 120)
            .put("silenceDurationMs", silence)
        val rtc = JSONObject()
            .put("automaticActivityDetection", vad)
            .put("activityHandling", if (interrupts) "START_OF_ACTIVITY_INTERRUPTS" else "NO_INTERRUPTION")
        if (extras) rtc.put("turnCoverage", "TURN_INCLUDES_ALL_INPUT")
        val resumption = JSONObject()
        if (handle != null) resumption.put("handle", handle)
        val out = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("realtimeInputConfig", rtc)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", resumption)
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        if (withTools) out.put("tools", tools())
        if (extras) out.put("proactivity", JSONObject().put("proactiveAudio", true))
        return out
    }
}
