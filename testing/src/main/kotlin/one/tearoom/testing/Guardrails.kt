package one.tearoom.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines
import kotlin.streams.asSequence

/**
 * The rules the apps' guardrail tests share. Every rule returns the lines that break it; the app's
 * test decides what to assert, so JUnit 4, JUnit 5 and kotlin.test all fit.
 *
 * `allowed` entries are paths relative to the source root, such as `ui/Theme.kt` or `ui/theme`
 * (a directory). A source is allowed when it, or one of its parent directories, ends with the entry.
 * A bare file name such as `Theme.kt` matches every file of that name; prefer the path.
 */
object Guardrails {
    /** Compose colour literals: `Color(0x…)`, the named `Color.*` constants, `"#RRGGBB"` and `argb(0x…)`. */
    val COLOUR_LITERALS = Regex("""Color\(0x|Color\.(Red|Blue|Green|Black|White|Gray|Yellow)\b|"#[0-9A-Fa-f]{6,8}"|\bargb\(0x""")

    /** Raw sizes: `16.dp`, `14.sp`, `0.5.dp`. */
    val RAW_SIZES = Regex("""\b\d+(\.\d+)?\.(dp|sp)\b""")

    /** Kotlin sources under the given roots. Fails when a root is missing or holds no source, so a wrong path cannot pass silently. */
    fun sources(vararg roots: Path, extension: String = "kt"): List<Path> {
        require(roots.isNotEmpty()) { "at least one source root is needed" }
        return roots.flatMap { root ->
            check(root.isDirectory()) { "source root not found: ${root.toAbsolutePath()}" }
            val files = Files.walk(root).use { stream ->
                stream.asSequence().filter { Files.isRegularFile(it) && it.extension == extension }.sorted().toList()
            }
            check(files.isNotEmpty()) { "no .$extension sources under ${root.toAbsolutePath()}" }
            files
        }
    }

    /** Colour literals outside the allowed files. */
    fun colourLiterals(sources: List<Path>, allowed: Set<Path> = emptySet(), pattern: Regex = COLOUR_LITERALS): List<Hit> =
        onlyIn(sources, pattern, allowed)

    /** `dp`/`sp` literals outside the allowed files (the theme and the shared components). */
    fun rawSizes(sources: List<Path>, allowed: Set<Path>, pattern: Regex = RAW_SIZES): List<Hit> =
        onlyIn(sources, pattern, allowed)

    /**
     * Layer rules: for every directory (relative to the source root, "" for all sources) the import
     * prefixes it must not use, for example `mapOf("domain" to listOf("android.", "androidx."))`.
     * A prefix is matched against the start of the import, so `android.` does not cover `androidx.`.
     */
    fun forbiddenImports(sources: List<Path>, rules: Map<String, List<String>>): List<Hit> =
        rules.flatMap { (under, prefixes) ->
            val scope = if (under.isBlank()) sources else sources.filter { isUnder(it, under) }
            scope.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val trimmed = line.trim()
                    if (prefixes.any { trimmed.startsWith("import $it") }) Hit(file, i + 1, trimmed) else null
                }
            }
        }

    /** Lines matching the pattern in any file that is not allowed, for example `openConnection` outside the one network client. */
    fun onlyIn(sources: List<Path>, pattern: Regex, allowed: Set<Path>): List<Hit> =
        sources.filterNot { isAllowed(it, allowed) }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (pattern.containsMatchIn(line)) Hit(file, i + 1, line.trim()) else null
            }
        }

    /**
     * Compares the translations under `resDir` with the default `values`: every translatable key in
     * every locale, no extra keys, `other` in every plural, the same number of array items and the
     * same format arguments (strings and the `other` item of plurals). See [TranslationReport].
     */
    fun translations(resDir: Path, locales: List<String>, filePattern: Regex = Regex("""strings.*\.xml""")): TranslationReport =
        Translations.compare(resDir, locales, filePattern)

    internal fun isAllowed(file: Path, allowed: Set<Path>): Boolean {
        if (allowed.isEmpty()) return false
        var current: Path? = file
        while (current != null) {
            if (allowed.any { current!!.endsWith(it) }) return true
            current = current.parent
        }
        return false
    }

    private fun isUnder(file: Path, under: String): Boolean {
        val dir = Path.of(under.trim('/'))
        var current = file.parent
        while (current != null) {
            if (current.endsWith(dir)) return true
            current = current.parent
        }
        return false
    }
}
