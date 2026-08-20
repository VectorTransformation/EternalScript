package eternalscript.scripting.runtime

import eternalscript.api.ScriptDiagnostic
import eternalscript.api.ScriptDiagnosticPhase
import eternalscript.api.ScriptOperation
import eternalscript.api.ScriptOperationStatus
import eternalscript.feedback.FeedbackKey
import eternalscript.feedback.SystemFeedback
import eternalscript.scripting.repl.SharedReplDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ScriptOperationReporterTest {
    @Test
    fun `result snapshots the current revision and input collections`() {
        var revision = 4L
        val affected = mutableListOf("one.eternal.kts")
        val diagnostics = mutableListOf(
            ScriptDiagnostic("one.eternal.kts", ScriptDiagnosticPhase.COMPILE, "failure")
        )
        val reporter = ScriptOperationReporter({ revision }) { error("must not log") }

        val result = reporter.result(
            ScriptOperation.RELOAD,
            ScriptOperationStatus.FAILED,
            affected,
            diagnostics
        )
        revision = 5
        affected += "two.eternal.kts"
        diagnostics.clear()

        assertEquals(4, result.revision)
        assertEquals(listOf("one.eternal.kts"), result.affectedPaths)
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun `diagnostic logging preserves the compiler cause`() {
        val logged = mutableListOf<SystemFeedback>()
        val cause = IllegalStateException("compiler failure")
        val internal = SharedReplDiagnostic(
            "broken.eternal.kts",
            "cannot compile",
            line = 3,
            column = 7,
            cause = cause
        )
        val reporter = ScriptOperationReporter({ 1 }, logged::add)

        reporter.logDiagnostic(internal.toScriptDiagnostic(ScriptDiagnosticPhase.COMPILE), internal)

        assertEquals(FeedbackKey.SYSTEM_DIAGNOSTIC, logged.single().text.key)
        assertSame(cause, logged.single().cause)
    }
}
