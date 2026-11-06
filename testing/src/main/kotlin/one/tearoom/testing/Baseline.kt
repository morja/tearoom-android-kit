package one.tearoom.testing

import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * A counted baseline for a rule an app does not meet yet: hits per file, recorded once and allowed
 * only to shrink. The file holds one `path: count` per line; `#` starts a comment.
 */
object Baseline {
    fun read(file: Path): Map<String, Int> =
        file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .associate { line ->
                val path = line.substringBeforeLast(':').trim()
                val count = line.substringAfterLast(':').trim().toIntOrNull()
                    ?: error("baseline line without a count: \"$line\" in $file")
                path to count
            }

    /** The text of a baseline that matches these hits, for creating or lowering the file. */
    fun render(hits: List<Hit>): String =
        counts(hits).entries.joinToString("\n", postfix = "\n") { "${it.key}: ${it.value}" }

    /** Compares the current hits with the baseline. */
    fun compare(hits: List<Hit>, baseline: Map<String, Int>): BaselineReport {
        val current = counts(hits)
        val grown = (current.keys + baseline.keys).sorted()
            .filter { current.getOrDefault(it, 0) > baseline.getOrDefault(it, 0) }
            .associateWith { Change(baseline.getOrDefault(it, 0), current.getOrDefault(it, 0)) }
        val shrunk = (current.keys + baseline.keys).sorted()
            .filter { current.getOrDefault(it, 0) < baseline.getOrDefault(it, 0) }
            .associateWith { Change(baseline.getOrDefault(it, 0), current.getOrDefault(it, 0)) }
        return BaselineReport(grown, shrunk, hits)
    }

    private fun counts(hits: List<Hit>): Map<String, Int> =
        hits.groupingBy { it.file.toString() }.eachCount().toSortedMap()

    data class Change(val before: Int, val now: Int)

    data class BaselineReport(val grown: Map<String, Change>, val shrunk: Map<String, Change>, val hits: List<Hit>) {
        /** No file has more hits than recorded. */
        val holds: Boolean get() = grown.isEmpty()

        /** Some file has fewer hits than recorded; lower the baseline so it stays honest. */
        val isStale: Boolean get() = shrunk.isNotEmpty()

        fun describeGrowth(): String = grown.entries.joinToString("\n") { (file, c) ->
            "$file: ${c.now} instead of ${c.before}\n" + hits.filter { it.file.toString() == file }.joinToString("\n") { "  $it" }
        }

        fun describeShrink(): String = shrunk.entries.joinToString("\n") { (file, c) -> "$file: ${c.before} -> ${c.now}" }
    }
}
