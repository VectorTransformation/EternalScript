package eternalscript.feedback

import eternalscript.api.ScriptDiagnosticPhase
import eternalscript.api.ScriptOperation
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptOperationFeedbackTest {
    @Test
    fun `maps every operation and diagnostic phase to its feedback key`() {
        assertEquals(
            listOf(
                FeedbackKey.OPERATION_RELOAD,
                FeedbackKey.OPERATION_RECOMPILE,
                FeedbackKey.OPERATION_LOAD,
                FeedbackKey.OPERATION_UNLOAD,
                FeedbackKey.OPERATION_CLEAR
            ),
            ScriptOperation.entries.map { operation -> operation.feedbackText().key }
        )
        assertEquals(
            listOf(
                FeedbackKey.PHASE_SOURCE,
                FeedbackKey.PHASE_COMPILE,
                FeedbackKey.PHASE_EVALUATE,
                FeedbackKey.PHASE_ACTIVATE,
                FeedbackKey.PHASE_ROLLBACK
            ),
            ScriptDiagnosticPhase.entries.map { phase -> phase.feedbackText().key }
        )
    }
}
