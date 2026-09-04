package eternalscript.messaging

import eternalscript.api.ScriptDiagnosticPhase
import eternalscript.api.ScriptOperation
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptOperationMessageTest {
    @Test
    fun `maps every operation and diagnostic phase to its message key`() {
        assertEquals(
            listOf(
                MessageKey.OPERATION_CHECK,
                MessageKey.OPERATION_RELOAD,
                MessageKey.OPERATION_RECOMPILE,
                MessageKey.OPERATION_ENABLE,
                MessageKey.OPERATION_DISABLE,
                MessageKey.OPERATION_CANCEL
            ),
            ScriptOperation.entries.map { operation -> operation.messageText().key }
        )
        assertEquals(
            listOf(
                MessageKey.PHASE_SOURCE,
                MessageKey.PHASE_COMPILE,
                MessageKey.PHASE_EVALUATE,
                MessageKey.PHASE_ACTIVATE,
                MessageKey.PHASE_ROLLBACK
            ),
            ScriptDiagnosticPhase.entries.map { phase -> phase.messageText().key }
        )
    }
}
