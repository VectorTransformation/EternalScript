package eternalscript.scripting.runtime

import eternalscript.api.ScriptDiagnostic
import eternalscript.api.ScriptDiagnosticPhase
import eternalscript.api.ScriptOperation
import eternalscript.api.ScriptOperationResult
import eternalscript.api.ScriptOperationStatus
import eternalscript.messaging.MessageKey
import eternalscript.messaging.MessageLevel
import eternalscript.messaging.SystemMessage
import eternalscript.messaging.messageText
import eternalscript.messaging.systemMessage
import eternalscript.scripting.repl.SharedReplDiagnostic
import java.util.List.copyOf

internal class ScriptOperationReporter(
    private val revision: () -> Long,
    private val system: (SystemMessage) -> Unit
) {
    fun result(
        operation: ScriptOperation,
        status: ScriptOperationStatus,
        affectedPaths: List<String> = emptyList(),
        diagnostics: List<ScriptDiagnostic> = emptyList()
    ): ScriptOperationResult = ScriptOperationResult(
        operation,
        status,
        revision(),
        copyOf(affectedPaths),
        copyOf(diagnostics)
    )

    fun logDiagnostic(diagnostic: ScriptDiagnostic, internal: SharedReplDiagnostic? = null) {
        system(
            systemMessage(
                MessageLevel.ERROR,
                MessageKey.SYSTEM_DIAGNOSTIC,
                "source" to diagnostic.source,
                "phase" to diagnostic.phase.messageText(),
                "line" to diagnostic.line,
                "column" to diagnostic.column,
                "message" to diagnostic.message,
                cause = internal?.cause
            )
        )
    }
}

internal fun SharedReplDiagnostic.toScriptDiagnostic(phase: ScriptDiagnosticPhase): ScriptDiagnostic =
    ScriptDiagnostic(source, phase, message, line, column)
