package one.tearoom.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class GuardrailsTest {
    private val root = Path.of("src/test/fixtures/app/src/main/java")
    private val sources = Guardrails.sources(root)

    private fun names(hits: List<Hit>) = hits.map { "${it.file.fileName}:${it.line}" }

    @Test
    fun sourcesFindsEveryKotlinFileAndFailsOnBadRoots() {
        assertEquals(listOf("Client.kt", "Model.kt", "Screen.kt", "Theme.kt"), sources.map { it.fileName.toString() })
        assertThrows(IllegalStateException::class.java) { Guardrails.sources(Path.of("src/test/fixtures/nowhere")) }
        assertThrows(IllegalStateException::class.java) { Guardrails.sources(Path.of("src/test/fixtures/app/src/main/res")) }
    }

    @Test
    fun colourLiteralsCoverEveryFormAndRespectAllowedFiles() {
        assertEquals(listOf("Screen.kt:4", "Screen.kt:5", "Screen.kt:6", "Theme.kt:3"), names(Guardrails.colourLiterals(sources)))
        assertEquals(listOf("Screen.kt:4", "Screen.kt:5", "Screen.kt:6"), names(Guardrails.colourLiterals(sources, setOf(Path.of("ui/theme/Theme.kt")))))
        assertEquals(listOf("Screen.kt:4"), names(Guardrails.colourLiterals(sources, emptySet(), Regex("""Color\.Red"""))))
    }

    @Test
    fun rawSizesAllowFilesAndDirectories() {
        assertEquals(listOf("Screen.kt:7", "Screen.kt:8", "Theme.kt:4", "Theme.kt:5"), names(Guardrails.rawSizes(sources, emptySet())))
        assertEquals(listOf("Screen.kt:7", "Screen.kt:8"), names(Guardrails.rawSizes(sources, setOf(Path.of("ui/theme")))))
        assertEquals(listOf("Screen.kt:7", "Screen.kt:8"), names(Guardrails.rawSizes(sources, setOf(Path.of("Theme.kt")))))
        assertEquals(emptyList<String>(), names(Guardrails.rawSizes(sources, setOf(Path.of("ui")))))
    }

    @Test
    fun forbiddenImportsMatchPrefixesUnderADirectory() {
        val domainOnly = Guardrails.forbiddenImports(sources, mapOf("domain" to listOf("android.", "androidx.")))
        assertEquals(listOf("import android.os.Bundle", "import androidx.annotation.Keep"), domainOnly.map { it.text })
        assertEquals(listOf("Model.kt:3", "Model.kt:4"), names(domainOnly))

        // "android." does not cover androidx; "" means every source.
        assertEquals(listOf("Model.kt:3"), names(Guardrails.forbiddenImports(sources, mapOf("domain/" to listOf("android.")))))
        assertEquals(listOf("Client.kt:3", "Model.kt:4"), names(Guardrails.forbiddenImports(sources, mapOf("" to listOf("androidx.")))))
        assertEquals(emptyList<String>(), names(Guardrails.forbiddenImports(sources, mapOf("ui" to listOf("android.")))))
    }

    @Test
    fun onlyInReportsThePatternOutsideTheAllowedFile() {
        val hits = Guardrails.onlyIn(sources, Regex("openConnection"), setOf(Path.of("data/Client.kt")))
        assertEquals(listOf("Screen.kt:9"), names(hits))
        assertEquals("val url = URL(\"x\").openConnection()", hits.single().text)
        assertTrue(hits.single().toString().endsWith("Screen.kt:9: val url = URL(\"x\").openConnection()"))
    }

    @Test
    fun baselineHoldsShrinksAndGrows() {
        val hits = Guardrails.rawSizes(sources, emptySet())
        val screen = hits.first().file.toString()
        val theme = hits.last().file.toString()

        val same = Baseline.compare(hits, mapOf(screen to 2, theme to 2))
        assertTrue(same.holds)
        assertTrue(!same.isStale)

        val lower = Baseline.compare(hits, mapOf(screen to 1, theme to 2))
        assertTrue(!lower.holds)
        assertEquals(Baseline.Change(1, 2), lower.grown[screen])
        assertTrue(lower.describeGrowth().contains("Screen.kt: 2 instead of 1"))

        val higher = Baseline.compare(hits, mapOf(screen to 2, theme to 3, "gone/File.kt" to 1))
        assertTrue(higher.holds)
        assertTrue(higher.isStale)
        assertEquals(setOf(theme, "gone/File.kt"), higher.shrunk.keys)
    }

    @Test
    fun baselineFilesRoundTrip() {
        val hits = Guardrails.rawSizes(sources, emptySet())
        val file = kotlin.io.path.createTempFile("baseline", ".txt")
        file.toFile().writeText("# raw dp and sp, may only shrink\n" + Baseline.render(hits))
        val read = Baseline.read(file)
        assertEquals(2, read.size)
        assertTrue(Baseline.compare(hits, read).holds)
        assertTrue(!Baseline.compare(hits, read).isStale)

        file.toFile().writeText("src/x/File.kt: many\n")
        assertThrows(IllegalStateException::class.java) { Baseline.read(file) }
    }
}
