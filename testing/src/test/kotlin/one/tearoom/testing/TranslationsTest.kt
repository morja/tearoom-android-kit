package one.tearoom.testing

import one.tearoom.testing.TranslationProblem.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class TranslationsTest {
    private val res = Path.of("src/test/fixtures/app/src/main/res")

    @Test
    fun keysComeFromEveryStringsFileWithoutUntranslatableOnes() {
        val report = Guardrails.translations(res, listOf("de"))
        assertEquals(setOf("hello", "greet", "untranslated", "raw", "days", "hours", "modes", "more"), report.baseKeys)
        assertEquals(setOf("de"), report.locales)
    }

    @Test
    fun everyKindOfProblemIsReported() {
        val report = Guardrails.translations(res, listOf("de", "fr"))
        assertTrue(!report.isClean)

        assertEquals(listOf("fr"), report.problems(Kind.MISSING_LOCALE).map { it.locale })
        assertEquals(listOf("untranslated"), report.problems(Kind.MISSING).map { it.key })
        assertEquals(listOf("extra"), report.problems(Kind.EXTRA).map { it.key })
        assertEquals(listOf("hours"), report.problems(Kind.PLURAL_WITHOUT_OTHER).map { it.key })
        assertEquals(listOf("modes"), report.problems(Kind.ARRAY_SIZE).map { it.key })
        // days: %s instead of %d in the "other" item; greet is fine (same arguments, other order); raw is formatted="false".
        val placeholders = report.problems(Kind.PLACEHOLDERS).single()
        assertEquals("days", placeholders.key)
        assertEquals("%1\$s instead of %1\$d", placeholders.detail)
        assertEquals(6, report.problems.size)
        assertTrue(report.toString().contains("values-de: untranslated MISSING"))
    }

    @Test
    fun placeholdersIgnorePositionsAndLiterals() {
        assertEquals(mapOf(1 to 's', 2 to 'd'), Translations.placeholders("%1\$s and %2\$d"))
        assertEquals(mapOf(1 to 's', 2 to 'd'), Translations.placeholders("%s and %d"))
        assertEquals(mapOf(1 to 's', 2 to 'd'), Translations.placeholders("%2\$d before %1\$s"))
        assertEquals(mapOf(1 to 'f'), Translations.placeholders("100%% at %.1f%n"))
        assertEquals(emptyMap<Int, Char>(), Translations.placeholders("plain"))
    }

    @Test
    fun aCleanLocaleHasNoProblems() {
        val report = Guardrails.translations(res, emptyList())
        assertTrue(report.isClean)
        assertEquals("8 keys in 0 locales, all translated", report.toString())
        assertThrows(IllegalStateException::class.java) { Guardrails.translations(Path.of("src/test/fixtures/nowhere"), listOf("de")) }
    }
}
