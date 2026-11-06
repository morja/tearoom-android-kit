package one.tearoom.testing

import java.nio.file.Path

/** One line that breaks a rule: the file as the caller passed it, the 1-based line and its trimmed text. */
data class Hit(val file: Path, val line: Int, val text: String) {
    override fun toString(): String = "$file:$line: $text"
}

/** Joins hits one per line, for an assertion message. */
fun List<Hit>.describe(): String = joinToString("\n")
