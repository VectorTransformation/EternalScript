package eternalScript.core.script.project

import eternalScript.core.script.generation.ScriptProjectCheckOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptProjectCheckCliPresenterTest {
    @Test
    fun `cli outcomes have explicit terminal summaries next actions and exit codes`() {
        val cases = listOf(
            ScriptProjectCheckOutcome.NO_SOURCES to "NO_SOURCES",
            ScriptProjectCheckOutcome.PASSED to "PASSED",
            ScriptProjectCheckOutcome.FAILED to "FAILED"
        )

        cases.forEach { (outcome, label) ->
            val lines = ScriptProjectCheckCliPresenter.lines(
                ScriptProjectCheckCliSummary(
                    outcome = outcome,
                    sourceCount = if (outcome == ScriptProjectCheckOutcome.NO_SOURCES) 0 else 2,
                    diagnosticCount = if (outcome == ScriptProjectCheckOutcome.FAILED) 1 else 0
                )
            )

            assertEquals(2, lines.size)
            assertTrue(lines.first().startsWith(label), outcome.name)
            assertTrue(lines.last().startsWith("Next:"), outcome.name)
        }

        assertEquals(1, ScriptProjectCheckCliPresenter.FAILED_EXIT_CODE)
        assertEquals(2, ScriptProjectCheckCliPresenter.NO_SOURCES_EXIT_CODE)
        assertTrue(
            ScriptProjectCheckCliPresenter.allowedEmptyLines().first().contains("allowed=true")
        )
    }
}
