package eternalscript.feedback

import eternalscript.api.ScriptDiagnosticPhase
import eternalscript.api.ScriptOperation

internal fun ScriptOperation.feedbackText(): FeedbackText = feedbackText(
    when (this) {
        ScriptOperation.RELOAD -> FeedbackKey.OPERATION_RELOAD
        ScriptOperation.RECOMPILE -> FeedbackKey.OPERATION_RECOMPILE
        ScriptOperation.LOAD -> FeedbackKey.OPERATION_LOAD
        ScriptOperation.UNLOAD -> FeedbackKey.OPERATION_UNLOAD
        ScriptOperation.CLEAR -> FeedbackKey.OPERATION_CLEAR
    }
)

internal fun ScriptDiagnosticPhase.feedbackText(): FeedbackText = feedbackText(
    when (this) {
        ScriptDiagnosticPhase.SOURCE -> FeedbackKey.PHASE_SOURCE
        ScriptDiagnosticPhase.COMPILE -> FeedbackKey.PHASE_COMPILE
        ScriptDiagnosticPhase.EVALUATE -> FeedbackKey.PHASE_EVALUATE
        ScriptDiagnosticPhase.ACTIVATE -> FeedbackKey.PHASE_ACTIVATE
        ScriptDiagnosticPhase.ROLLBACK -> FeedbackKey.PHASE_ROLLBACK
    }
)
