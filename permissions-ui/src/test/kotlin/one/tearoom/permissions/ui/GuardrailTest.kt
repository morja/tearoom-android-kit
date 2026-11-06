package one.tearoom.permissions.ui

import one.tearoom.testing.Guardrails
import one.tearoom.testing.Hit
import one.tearoom.testing.describe
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** The module's own rules: sizes only in PermissionStyle.kt, no colour of its own anywhere. */
class GuardrailTest {
    private val sources = Guardrails.sources(Path.of("src/main/kotlin"))

    @Test fun sizesOnlyInStyle() =
        assertNoHits("dp and sp belong in PermissionStyle.kt", Guardrails.rawSizes(sources, setOf(Path.of("one/tearoom/permissions/ui/PermissionStyle.kt"))))

    @Test fun noColourLiterals() =
        assertNoHits("colours come from the host app's MaterialTheme", Guardrails.colourLiterals(sources))

    private fun assertNoHits(rule: String, hits: List<Hit>) = assertTrue("$rule:\n${hits.describe()}", hits.isEmpty())
}
