package one.tearoom.testing

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.streams.asSequence
import org.w3c.dom.Element

/** What is wrong with one key in one locale; [key] is null when the whole locale is missing. */
data class TranslationProblem(val locale: String, val key: String?, val kind: Kind, val detail: String) {
    enum class Kind { MISSING_LOCALE, MISSING, EXTRA, PLURAL_WITHOUT_OTHER, ARRAY_SIZE, PLACEHOLDERS }

    override fun toString(): String = "values-$locale: ${key ?: ""} $kind $detail".trim()
}

/** The result of [Guardrails.translations]: the default keys, the locales that were found and every problem. */
data class TranslationReport(val baseKeys: Set<String>, val locales: Set<String>, val problems: List<TranslationProblem>) {
    val isClean: Boolean get() = problems.isEmpty()

    fun problems(kind: TranslationProblem.Kind): List<TranslationProblem> = problems.filter { it.kind == kind }

    override fun toString(): String =
        if (isClean) "${baseKeys.size} keys in ${locales.size} locales, all translated"
        else problems.joinToString("\n")
}

/** Parses the resources as XML, so attribute order, comments and xliff tags do not matter. */
internal object Translations {
    private class Entry(val type: String, val formatted: Boolean, val text: String?, val items: Map<String, String>)

    fun compare(resDir: Path, locales: List<String>, filePattern: Regex): TranslationReport {
        val base = read(resDir.resolve("values"), filePattern)
        check(base.isNotEmpty()) { "no string resources under ${resDir.resolve("values").toAbsolutePath()}" }
        val baseKeys = base.keys
        val problems = mutableListOf<TranslationProblem>()
        val found = mutableSetOf<String>()

        for (locale in locales) {
            val dir = resDir.resolve("values-$locale")
            if (!dir.isDirectory()) {
                problems += TranslationProblem(locale, null, TranslationProblem.Kind.MISSING_LOCALE, "folder ${dir.name} not found")
                continue
            }
            found += locale
            val other = read(dir, filePattern)
            (baseKeys - other.keys).sorted().forEach { problems += TranslationProblem(locale, it, TranslationProblem.Kind.MISSING, "") }
            (other.keys - baseKeys).sorted().forEach { problems += TranslationProblem(locale, it, TranslationProblem.Kind.EXTRA, "not in values or not translatable") }
            for ((key, translated) in other) {
                val original = base[key] ?: continue
                problems += check(locale, key, original, translated)
            }
        }
        return TranslationReport(baseKeys, found, problems)
    }

    private fun check(locale: String, key: String, original: Entry, translated: Entry): List<TranslationProblem> {
        val problems = mutableListOf<TranslationProblem>()
        when (original.type) {
            "plurals" -> {
                if ("other" !in translated.items) {
                    problems += TranslationProblem(locale, key, TranslationProblem.Kind.PLURAL_WITHOUT_OTHER, "quantities ${translated.items.keys}")
                } else if (original.formatted && translated.formatted) {
                    placeholderProblem(locale, key, original.items["other"], translated.items["other"])?.let { problems += it }
                }
            }
            "string-array" -> if (original.items.size != translated.items.size) {
                problems += TranslationProblem(locale, key, TranslationProblem.Kind.ARRAY_SIZE, "${translated.items.size} items instead of ${original.items.size}")
            }
            else -> if (original.formatted && translated.formatted) {
                placeholderProblem(locale, key, original.text, translated.text)?.let { problems += it }
            }
        }
        return problems
    }

    private fun placeholderProblem(locale: String, key: String, original: String?, translated: String?): TranslationProblem? {
        val want = placeholders(original ?: return null)
        val have = placeholders(translated ?: return null)
        return if (want == have) null
        else TranslationProblem(locale, key, TranslationProblem.Kind.PLACEHOLDERS, "${render(have)} instead of ${render(want)}")
    }

    private val placeholder = Regex("""%(\d+)?(\$)?([-#+ 0,(]*\d*(?:\.\d+)?)([a-zA-Z%])""")

    /** Format arguments as index to conversion, so `%1$s %2$d` equals `%2$d %1$s` and `%s %s`. `%%` and `%n` carry no argument. */
    internal fun placeholders(text: String): Map<Int, Char> {
        val result = mutableMapOf<Int, Char>()
        var next = 1
        for (match in placeholder.findAll(text)) {
            val conversion = match.groupValues[4][0]
            if (conversion == '%' || conversion == 'n') continue
            val index = match.groupValues[1].takeIf { it.isNotEmpty() && match.groupValues[2].isNotEmpty() }?.toInt() ?: next++
            result[index] = conversion
        }
        return result
    }

    private fun render(args: Map<Int, Char>): String =
        if (args.isEmpty()) "no arguments" else args.toSortedMap().entries.joinToString(" ") { "%${it.key}$${it.value}" }

    private fun read(dir: Path, filePattern: Regex): Map<String, Entry> {
        if (!dir.isDirectory()) return emptyMap()
        val files = Files.list(dir).use { s -> s.asSequence().filter { filePattern.matches(it.name) }.sorted().toList() }
        val entries = linkedMapOf<String, Entry>()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        for (file in files) {
            val root = factory.newDocumentBuilder().parse(file.toFile()).documentElement
            for (node in root.childNodes.asList()) {
                if (node !is Element || node.tagName !in setOf("string", "string-array", "plurals")) continue
                if (node.getAttribute("translatable") == "false") continue
                val name = node.getAttribute("name")
                val formatted = node.getAttribute("formatted") != "false"
                val items = node.getElementsByTagName("item").asList().filterIsInstance<Element>()
                    .mapIndexed { i, item -> (item.getAttribute("quantity").ifEmpty { i.toString() }) to item.textContent }
                    .toMap()
                entries[name] = Entry(node.tagName, formatted, node.textContent.takeIf { node.tagName == "string" }, items)
            }
        }
        return entries
    }

    private fun org.w3c.dom.NodeList.asList() = (0 until length).map { item(it) }
}
