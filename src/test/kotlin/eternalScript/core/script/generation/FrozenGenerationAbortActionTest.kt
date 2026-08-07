package eternalScript.core.script.generation

import eternalScript.core.script.data.ScriptExecutionGate
import kotlin.test.Test
import kotlin.test.assertEquals

class FrozenGenerationAbortActionTest {
    @Test
    fun `frozen generation can reopen before tracked work cancellation starts`() {
        assertEquals(
            FrozenGenerationAbortAction.RESTORE,
            frozenGenerationAbortAction(trackedWorkCancellationStarted = false)
        )
    }

    @Test
    fun `cancelled generation must stay frozen until deferred cleanup drains`() {
        assertEquals(
            FrozenGenerationAbortAction.KEEP_FROZEN,
            frozenGenerationAbortAction(trackedWorkCancellationStarted = true)
        )
    }

    @Test
    fun `unload retries drain for an already frozen generation`() {
        assertEquals(
            FrozenGenerationClearPreparation.RETRY_DRAIN,
            clearPreparation(ScriptExecutionGate.State.SWAPPING)
        )
    }

    @Test
    fun `frozen active reference is reported as no active generation`() {
        val generation = Any()

        assertEquals(
            ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE,
            resolveScriptProjectLoadOutcome(
                activated = false,
                expected = generation,
                current = generation,
                generation = ScriptProjectGenerationSnapshot(
                    state = ScriptExecutionGate.State.SWAPPING,
                    sourceNames = setOf("main.kt"),
                    entryNames = listOf("Main")
                )
            )
        )
    }
}
