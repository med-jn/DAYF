package app.dayf

import android.util.Xml
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipFile

/** A piece of a source: small enough to hand to the agent, big enough to keep its meaning. */
class Chunk(val idx: Int, val heading: String, val text: String, val page: Int)

/**
 * Everything here runs on the phone and costs no AI tokens: HTML, Word, EPUB, PowerPoint, Excel and CSV are
 * converted to Markdown locally. Only PDFs, images and videos need Gemini (see Ingest).
 */
object Md {
    class Page(val title: String, val md: String, val links: List<String>)

    // ------------------------------------------------------------------ HTML
    private const val DROP =
        "script,style,noscript,nav,footer,aside,form,iframe,svg,canvas,button,select,input,dialog,template," +
            "[role=navigation],[role=banner],[role=contentinfo],[aria-hidden=true],.cookie,.cookies,.advert,.ads," +
            ".sidebar,.breadcrumb,.breadcrumbs,.share,.social,.newsletter,.comments,#comments"
    private val ROOTS = listOf(
        "article", "main", "[role=main]", "#content", "#main-content", ".markdown-body", ".post-content",
        ".entry-content", ".article-body", ".article-content", ".content"
    )
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "main", "header", "figure", "figcaption", "details", "summary",
        "dl", "dt", "dd", "address", "center", "li", "ul", "ol", "tr", "td", "th", "body", "html"
    )

    /** `shift` pushes every heading down (used when several pages are merged under one title). */
    fun fromHtml(bytes: ByteArray, baseUrl: String, shift: Int = 0, wholeBody: Boolean = false): Page {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, baseUrl)
        val links = doc.select("a[href]").map { it.absUrl("href") }.filter { it.startsWith("http") }.distinct()
        val title = pageTitle(doc)
        doc.select(DROP).remove()
        val root = if (wholeBody) doc.body() else pickRoot(doc)
        val sb = StringBuilder()
        walk(root, sb, shift, 0)
        return Page(title, tidy(sb.toString()), links)
    }

    private fun pageTitle(doc: Document): String {
        var t = doc.select("meta[property=og:title]").attr("content").trim()
        if (t.isBlank()) t = doc.title().trim()
        if (t.isBlank()) t = doc.select("h1").first()?.text()?.trim() ?: ""
        if (t.contains(" | ")) {
            val head = t.substringBefore(" | ").trim()
            if (head.length >= 8) t = head
        }
        return t.take(120)
    }

    private fun pickRoot(doc: Document): Element {
        val body = doc.body()
        val total = body.text().length
        for (sel in ROOTS) {
            val best = doc.select(sel).maxByOrNull { it.text().length } ?: continue
            val len = best.text().length
            if (len >= 400 && len >= total * 0.35) return best
        }
        return body
    }

    private fun blank(sb: StringBuilder) {
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        if (sb.isEmpty()) return
        if (!sb.endsWith("\n\n")) sb.append(if (sb.endsWith("\n")) "\n" else "\n\n")
    }

    private fun appendText(sb: StringBuilder, raw: String) {
        var s = raw.replace(Regex("\\s+"), " ")
        if (sb.isEmpty() || sb.last() == '\n' || sb.last() == ' ') s = s.trimStart()
        if (s.isNotEmpty()) sb.append(s)
    }

    private fun walk(n: Node, sb: StringBuilder, shift: Int, depth: Int) {
        if (n is TextNode) {
            appendText(sb, n.text())
            return
        }
        if (n !is Element) return
        val tag = n.tagName().lowercase()
        when {
            tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> {
                val t = n.text().trim()
                if (t.isNotEmpty()) {
                    blank(sb)
                    sb.append("#".repeat(((tag[1] - '0') + shift).coerceIn(1, 6))).append(' ').append(t).append("\n\n")
                }
            }
            tag == "br" -> sb.append('\n')
            tag == "hr" -> blank(sb)
            tag == "ul" || tag == "ol" -> {
                blank(sb)
                var i = 1
                for (li in n.children()) {
                    if (li.tagName().lowercase() != "li") continue
                    val tmp = StringBuilder()
                    for (c in li.childNodes()) walk(c, tmp, shift, depth + 1)
                    val txt = tmp.toString().trim().replace(Regex("\n{2,}"), "\n")
                    if (txt.isEmpty()) continue
                    sb.append("  ".repeat(depth)).append(if (tag == "ol") "${i++}. " else "- ").append(txt).append('\n')
                }
                sb.append('\n')
            }
            tag == "table" -> table(n, sb)
            tag == "pre" -> {
                blank(sb)
                sb.append("```\n").append(n.wholeText().trimEnd()).append("\n```\n\n")
            }
            tag == "blockquote" -> {
                val tmp = StringBuilder()
                for (c in n.childNodes()) walk(c, tmp, shift, depth)
                val q = tidy(tmp.toString())
                if (q.isNotEmpty()) {
                    blank(sb)
                    sb.append(q.lines().joinToString("\n") { "> $it" }).append("\n\n")
                }
            }
            tag == "img" -> {
                val alt = n.attr("alt").trim()
                if (alt.length >= 4) appendText(sb, "[صورة: $alt]")
            }
            tag in BLOCK_TAGS -> {
                blank(sb)
                for (c in n.childNodes()) walk(c, sb, shift, depth)
                blank(sb)
            }
            else -> for (c in n.childNodes()) walk(c, sb, shift, depth)
        }
    }

    private fun table(t: Element, sb: StringBuilder) {
        val data = t.select("tr").map { r ->
            r.children().toList().filter { it.tagName() == "td" || it.tagName() == "th" }
                .map { it.text().replace('|', '/').replace("\n", " ").trim() }
        }.filter { it.isNotEmpty() }
        if (data.isEmpty()) return
        blank(sb)
        sb.append(tableMd(data)).append('\n')
    }

    fun tableMd(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val w = rows.maxOf { it.size }
        val sb = StringBuilder()
        rows.forEachIndexed { i, r ->
            val cells = r + List(w - r.size) { "" }
            sb.append("| ").append(cells.joinToString(" | ") { it.replace("\n", " ") }).append(" |\n")
            if (i == 0) sb.append("|").append(" --- |".repeat(w)).append('\n')
        }
        return sb.toString()
    }

    /** Collapses blank runs and repeated short lines (menus that appear twice, etc.). */
    fun tidy(s: String): String {
        val out = StringBuilder()
        var blanks = 0
        var prev = ""
        for (raw in s.replace("\r", "").split("\n")) {
            val l = raw.trimEnd()
            if (l.isBlank()) {
                blanks++
                if (blanks == 1 && out.isNotEmpty()) out.append('\n')
                continue
            }
            if (l == prev && l.length < 80 && !l.startsWith("|") && !l.startsWith("```")) continue
            blanks = 0
            out.append(l).append('\n')
            prev = l
        }
        return out.toString().trim()
    }

    // ------------------------------------------------------------------ plain text
    private val CHAPTER = Regex("^(الفصل|الباب|القسم|الجزء|المقدمة|الخاتمة|مقدمة|خاتمة|chapter|part|section|introduction|conclusion)\\b.{0,80}$", RegexOption.IGNORE_CASE)

    /** Plain text → Markdown; chapter-like lines become headings so books keep their structure. */
    fun fromPlain(text: String): String {
        val t = text.replace("\r\n", "\n").replace("\r", "\n").replace("\u0000", "")
        if (Regex("(?m)^#{1,4}\\s+\\S").containsMatchIn(t)) return t.trim()
        val sb = StringBuilder()
        for (line in t.split("\n")) {
            val l = line.trim()
            if (l.length in 2..90 && CHAPTER.matches(l)) sb.append("\n## ").append(l).append("\n\n") else sb.append(line).append('\n')
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ CSV
    fun csvToMd(text: String): String {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
        val delim = listOf(',', ';', '\t', '|').maxByOrNull { d -> first.count { it == d } } ?: ','
        val rows = ArrayList<List<String>>()
        var cell = StringBuilder()
        var row = ArrayList<String>()
        var quoted = false
        var i = 0
        while (i < text.length && rows.size < 20000) {
            val ch = text[i]
            when {
                quoted -> if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(ch)
                ch == '"' -> quoted = true
                ch == delim -> { row.add(cell.toString().trim()); cell = StringBuilder() }
                ch == '\n' -> {
                    row.add(cell.toString().trim()); cell = StringBuilder()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = ArrayList()
                }
                ch == '\r' -> {}
                else -> cell.append(ch)
            }
            i++
        }
        row.add(cell.toString().trim())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return tableMd(rows)
    }

    // ------------------------------------------------------------------ zip based formats
    private fun XmlPullParser.local(): String = (name ?: "").substringAfter(':')
    private fun XmlPullParser.attr(n: String): String {
        for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':') == n) return getAttributeValue(i) ?: ""
        return ""
    }

    private fun parser(ins: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        return p
    }

    private val HEADING = Regex("(heading|titre|überschrift|عنوان|título)\\s*(\\d)", RegexOption.IGNORE_CASE)

    private fun headingLevel(style: String): Int {
        if (style.equals("title", true)) return 1
        val m = HEADING.find(style) ?: return 0
        return m.groupValues[2].toIntOrNull()?.coerceIn(1, 6) ?: 0
    }

    fun docx(f: File): String {
        ZipFile(f).use { z ->
            val e = z.getEntry("word/document.xml") ?: return ""
            z.getInputStream(e).use { ins ->
                val p = parser(ins)
                val out = StringBuilder()
                val para = StringBuilder()
                var level = 0
                var bullet = false
                var tbl = 0
                var rowIdx = 0
                var row = ArrayList<String>()
                val cell = StringBuilder()
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.local()) {
                            "p" -> { para.setLength(0); level = 0; bullet = false }
                            "pStyle" -> level = headingLevel(p.attr("val"))
                            "numPr" -> bullet = true
                            "t" -> para.append(p.nextText())
                            "tab", "br", "cr" -> para.append(' ')
                            "tbl" -> { tbl++; rowIdx = 0 }
                            "tr" -> row = ArrayList()
                            "tc" -> cell.setLength(0)
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (p.local()) {
                            "p" -> {
                                val t = para.toString().trim()
                                if (tbl > 0) cell.append(t).append(' ')
                                else if (t.isNotEmpty()) {
                                    if (level > 0) out.append("#".repeat(level)).append(' ').append(t).append("\n\n")
                                    else if (bullet) out.append("- ").append(t).append('\n')
                                    else out.append(t).append("\n\n")
                                }
                                para.setLength(0)
                            }
                            "tc" -> row.add(cell.toString().trim().replace("|", "/"))
                            "tr" -> {
                                if (row.isNotEmpty()) {
                                    out.append("| ").append(row.joinToString(" | ")).append(" |\n")
                                    if (rowIdx == 0) out.append("|").append(" --- |".repeat(row.size)).append('\n')
                                    rowIdx++
                                }
                            }
                            "tbl" -> { tbl = (tbl - 1).coerceAtLeast(0); out.append('\n') }
                        }
                    }
                    ev = p.next()
                }
                return out.toString()
            }
        }
    }

    /** Paragraph texts of a DrawingML / generic xml part (a:p / a:t). */
    private fun xmlParas(ins: InputStream): List<String> {
        val p = parser(ins)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.local() == "t") cur.append(p.nextText())
            else if (ev == XmlPullParser.END_TAG && p.local() == "p") {
                val t = cur.toString().trim()
                if (t.isNotEmpty()) out.add(t)
                cur.setLength(0)
            }
            ev = p.next()
        }
        return out
    }

    private fun numberOf(name: String): Int = Regex("(\\d+)\\.xml$").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun pptx(f: File): String {
        ZipFile(f).use { z ->
            val slides = z.entries().toList().filter { Regex("^ppt/slides/slide\\d+\\.xml$").matches(it.name) }.sortedBy { numberOf(it.name) }
            val sb = StringBuilder()
            for (s in slides) {
                val n = numberOf(s.name)
                val paras = z.getInputStream(s).use { xmlParas(it) }
                val notes = z.getEntry("ppt/notesSlides/notesSlide$n.xml")?.let { e -> z.getInputStream(e).use { xmlParas(it) } } ?: emptyList()
                if (paras.isEmpty() && notes.isEmpty()) continue
                sb.append("## شريحة ").append(n).append("\n\n")
                paras.forEachIndexed { i, t -> sb.append(if (i == 0) "**$t**\n\n" else "- $t\n") }
                if (notes.isNotEmpty()) sb.append("\nملاحظات المقدّم: ").append(notes.joinToString(" ")).append('\n')
                sb.append('\n')
            }
            return sb.toString()
        }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return (n - 1).coerceAtLeast(0)
    }

    fun xlsx(f: File): String {
        ZipFile(f).use { z ->
            val shared = ArrayList<String>()
            z.getEntry("xl/sharedStrings.xml")?.let { e ->
                z.getInputStream(e).use { ins ->
                    val p = parser(ins)
                    val cur = StringBuilder()
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && p.local() == "t") cur.append(p.nextText())
                        else if (ev == XmlPullParser.END_TAG && p.local() == "si") { shared.add(cur.toString()); cur.setLength(0) }
                        ev = p.next()
                    }
                }
            }
            val names = ArrayList<String>()
            z.getEntry("xl/workbook.xml")?.let { e ->
                z.getInputStream(e).use { ins ->
                    val p = parser(ins)
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && p.local() == "sheet") names.add(p.attr("name"))
                        ev = p.next()
                    }
                }
            }
            val sheets = z.entries().toList().filter { Regex("^xl/worksheets/sheet\\d+\\.xml$").matches(it.name) }.sortedBy { numberOf(it.name) }
            val sb = StringBuilder()
            sheets.forEachIndexed { si, se ->
                val rows = ArrayList<List<String>>()
                z.getInputStream(se).use { ins ->
                    val p = parser(ins)
                    var row = ArrayList<String>()
                    var col = 0
                    var type = ""
                    val v = StringBuilder()
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT && rows.size < 20000) {
                        if (ev == XmlPullParser.START_TAG) {
                            when (p.local()) {
                                "row" -> row = ArrayList()
                                "c" -> { col = colIndex(p.attr("r")); type = p.attr("t"); v.setLength(0) }
                                "v", "t" -> v.append(p.nextText())
                            }
                        } else if (ev == XmlPullParser.END_TAG) {
                            when (p.local()) {
                                "c" -> {
                                    while (row.size < col) row.add("")
                                    val raw = v.toString()
                                    row.add(if (type == "s") shared.getOrElse(raw.toIntOrNull() ?: -1) { "" } else raw)
                                }
                                "row" -> if (row.any { it.isNotBlank() }) rows.add(row)
                            }
                        }
                        ev = p.next()
                    }
                }
                if (rows.isNotEmpty()) {
                    sb.append("## ").append(names.getOrElse(si) { "ورقة ${si + 1}" }).append("\n\n").append(tableMd(rows)).append('\n')
                }
            }
            return sb.toString()
        }
    }

    /** Returns (title, markdown). */
    fun epub(f: File): Pair<String, String> {
        ZipFile(f).use { z ->
            val container = z.getEntry("META-INF/container.xml") ?: return "" to ""
            var opfPath = ""
            z.getInputStream(container).use { ins ->
                val p = parser(ins)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.local() == "rootfile" && opfPath.isEmpty()) opfPath = p.attr("full-path")
                    ev = p.next()
                }
            }
            val opf = z.getEntry(opfPath) ?: return "" to ""
            val dir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') + "/" else ""
            val manifest = HashMap<String, String>()
            val spine = ArrayList<String>()
            var title = ""
            z.getInputStream(opf).use { ins ->
                val p = parser(ins)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.local()) {
                            "item" -> manifest[p.attr("id")] = p.attr("href")
                            "itemref" -> spine.add(p.attr("idref"))
                            "title" -> if (title.isEmpty()) title = p.nextText().trim()
                        }
                    }
                    ev = p.next()
                }
            }
            val sb = StringBuilder()
            for (id in spine) {
                val href = manifest[id] ?: continue
                val path = dir + URLDecoder.decode(href.substringBefore('#'), "UTF-8")
                val e = z.getEntry(path) ?: continue
                val bytes = z.getInputStream(e).use { it.readBytes() }
                val md = fromHtml(bytes, "http://epub.local/", 0, true).md
                if (md.isNotBlank()) sb.append(md).append("\n\n")
            }
            return title to sb.toString()
        }
    }

    // ------------------------------------------------------------------ chunking
    private val HEAD = Regex("^(#{1,6})\\s+(.*)$")
    private val PAGE = Regex("^\\[\\[p(\\d+)]]\\s*$")

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun chunk(md: String, maxLen: Int = 1400, minLen: Int = 450): List<Chunk> {
        val out = ArrayList<Chunk>()
        val path = arrayOfNulls<String>(7)
        val buf = StringBuilder()
        val par = ArrayList<String>()
        var page = 0
        var bufHeading = ""
        var bufPage = 0

        fun crumb() = path.filterNotNull().joinToString(" › ").take(240)

        fun flush() {
            val t = buf.toString().trim()
            if (t.isEmpty()) return
            if (t.lines().all { it.startsWith("#") }) return // heading only: keep it for the content that follows
            out.add(Chunk(out.size, bufHeading, t, bufPage))
            buf.setLength(0)
        }

        fun addPar() {
            if (par.isEmpty()) return
            val text = par.joinToString("\n")
            par.clear()
            val pieces = if (text.length > maxLen) splitBig(text, maxLen) else listOf(text)
            for (piece in pieces) {
                if (buf.isNotEmpty() && buf.length + piece.length + 2 > maxLen) flush()
                if (buf.isEmpty()) { bufHeading = crumb(); bufPage = page }
                buf.append(piece).append("\n\n")
            }
        }

        for (line in md.split("\n")) {
            val pm = PAGE.matchEntire(line.trim())
            if (pm != null) {
                page = pm.groupValues[1].toIntOrNull() ?: page
                continue
            }
            val hm = HEAD.matchEntire(line.trimEnd())
            if (hm != null) {
                addPar()
                val level = hm.groupValues[1].length
                val text = hm.groupValues[2].trim().trim('#', ' ', '*')
                if (buf.length >= minLen || level <= 2) flush()
                path[level] = text
                for (i in level + 1..6) path[i] = null
                if (buf.isEmpty()) { bufHeading = crumb(); bufPage = page }
                buf.append("#".repeat(level)).append(' ').append(text).append("\n\n")
                continue
            }
            if (line.isBlank()) addPar() else par.add(line)
        }
        addPar()
        // final flush even if only a heading remains
        val rest = buf.toString().trim()
        if (rest.isNotEmpty()) out.add(Chunk(out.size, bufHeading, rest, bufPage))
        return out
    }

    private fun pack(units: List<String>, sep: String, maxLen: Int, prefix: String): List<String> {
        val res = ArrayList<String>()
        val cur = StringBuilder()
        fun push() {
            if (cur.isNotEmpty()) res.add(cur.toString().trim())
            cur.setLength(0)
        }
        for (u0 in units) {
            val pieces = if (u0.length > maxLen) u0.chunked(maxLen) else listOf(u0)
            for (u in pieces) {
                if (cur.isNotEmpty() && cur.length + u.length + sep.length > maxLen) push()
                if (cur.isEmpty() && res.isNotEmpty() && prefix.isNotEmpty()) cur.append(prefix)
                cur.append(u).append(sep)
            }
        }
        push()
        return res
    }

    private fun splitBig(text: String, maxLen: Int): List<String> {
        val lines = text.split("\n")
        if (lines.size > 3) {
            val isTable = lines[0].startsWith("|") && lines[1].contains("---")
            return if (isTable) pack(lines.drop(2), "\n", maxLen - 200, lines[0] + "\n" + lines[1] + "\n").let { parts ->
                // first part must carry the header too
                if (parts.isNotEmpty()) listOf(lines[0] + "\n" + lines[1] + "\n" + parts[0]) + parts.drop(1) else parts
            } else pack(lines, "\n", maxLen, "")
        }
        val sentences = text.split(Regex("(?<=[.!?؟؛。])\\s+"))
        return pack(sentences, " ", maxLen, "")
    }

    // ------------------------------------------------------------------ outline and brief
    private class Head(val title: String, val part: Int, val page: Int)

    /** The map the agent reads first: where each topic lives (part numbers are 1-based). */
    fun outline(chunks: List<Chunk>, max: Int = 3800): String {
        val seen = LinkedHashMap<String, Head>()
        for (c in chunks) if (c.heading.isNotBlank() && !seen.containsKey(c.heading)) seen[c.heading] = Head(c.heading, c.idx + 1, c.page)
        fun render(items: Collection<Head>): String {
            val sb = StringBuilder()
            for (h in items) {
                val line = "• ${h.title} (جزء ${h.part}${if (h.page > 0) "، ص ${h.page}" else ""})\n"
                if (sb.length + line.length > max) { sb.append("• …\n"); break }
                sb.append(line)
            }
            return sb.toString().trim()
        }
        if (seen.size >= 2) {
            val full = render(seen.values)
            if (!full.endsWith("…")) return full
            val top = LinkedHashMap<String, Head>()
            for (h in seen.values) {
                val k = h.title.substringBefore(" › ")
                if (!top.containsKey(k)) top[k] = Head(k, h.part, h.page)
            }
            return render(top.values)
        }
        // no headings: sample the opening words of evenly spaced parts
        val step = maxOf(1, chunks.size / 40)
        val pts = ArrayList<Head>()
        var i = 0
        while (i < chunks.size) {
            val c = chunks[i]
            pts.add(Head(c.text.replace(Regex("\\s+"), " ").take(50) + "…", c.idx + 1, c.page))
            i += step
        }
        return render(pts)
    }

    fun brief(md: String): String {
        val first = md.split("\n\n").map { it.trim() }
            .firstOrNull { it.length >= 60 && !it.startsWith("#") && !it.startsWith("|") && !it.startsWith("[[") && !it.startsWith("```") }
            ?.replace(Regex("\\s+"), " ")?.take(130) ?: ""
        val heads = md.lineSequence().filter { it.startsWith("# ") || it.startsWith("## ") }
            .map { it.trimStart('#', ' ').take(32) }.distinct().take(5).toList()
        val sb = StringBuilder(first)
        if (heads.size >= 2) {
            if (sb.isNotEmpty()) sb.append(" • ")
            sb.append("المحاور: ").append(heads.joinToString("، "))
        }
        return sb.toString().take(260)
    }

    fun words(md: String): Int = md.split(Regex("\\s+")).count { it.isNotBlank() }
}
