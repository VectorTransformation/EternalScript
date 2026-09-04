package eternalscript.messaging

import eternalscript.api.ScriptDiagnosticPhase
import eternalscript.api.ScriptOperation

internal fun ScriptOperation.messageText(): MessageText = messageText(
    when (this) {
        ScriptOperation.CHECK -> MessageKey.OPERATION_CHECK
        ScriptOperation.RELOAD -> MessageKey.OPERATION_RELOAD
        ScriptOperation.RECOMPILE -> MessageKey.OPERATION_RECOMPILE
        ScriptOperation.ENABLE -> MessageKey.OPERATION_ENABLE
        ScriptOperation.DISABLE -> MessageKey.OPERATION_DISABLE
        ScriptOperation.CANCEL -> MessageKey.OPERATION_CANCEL
    }
)

internal fun ScriptDiagnosticPhase.messageText(): MessageText = messageText(
    when (this) {
        ScriptDiagnosticPhase.SOURCE -> MessageKey.PHASE_SOURCE
        ScriptDiagnosticPhase.COMPILE -> MessageKey.PHASE_COMPILE
        ScriptDiagnosticPhase.EVALUATE -> MessageKey.PHASE_EVALUATE
        ScriptDiagnosticPhase.ACTIVATE -> MessageKey.PHASE_ACTIVATE
        ScriptDiagnosticPhase.ROLLBACK -> MessageKey.PHASE_ROLLBACK
    }
)
