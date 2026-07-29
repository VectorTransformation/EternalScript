package eternalScript.core.script.project

import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ScriptProjectDiagnosticTest {
    @Test
    fun `generated compiler failures map to the original Kotlin source`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile("a.kt", "package sample.valid\n\nfun valid() = 1"),
                ScriptProjectFile(
                    "nested/b.kt",
                    """
                    package sample.broken

                    fun broken(): Int = missingValue
                    fun stillMapped() = Unit
                    """.trimIndent()
                )
            )
        )
        val generated = project.module.files.single { file ->
            file.text.contains("fun broken(): Int = missingValue")
        }
        val generatedLine = generated.text.lines()
            .indexOf("fun broken(): Int = missingValue") + 1
        val generatedColumn = generated.text.lines()[generatedLine - 1]
            .indexOf("missingValue") + 1
        val location = SourceCode.Position(generatedLine, generatedColumn)
        val result = ResultWithDiagnostics.Failure(
            ScriptDiagnostic(
                ScriptDiagnostic.unspecifiedError,
                "Unresolved reference 'missingValue'.",
                ScriptDiagnostic.Severity.ERROR,
                generated.name,
                SourceCode.Location(location)
            )
        )

        val diagnostic = project.failureDiagnostics(result)
            .firstOrNull { it.report.message.contains("missingValue") }

        assertNotNull(diagnostic)
        assertEquals("nested/b.kt", diagnostic.sourceName)
        assertEquals(3, diagnostic.line)
        assertEquals(21, diagnostic.column)
    }

    @Test
    fun `runtime failures map to the original Kotlin source`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile("a.kt", "package sample.valid\n\nfun valid() = 1"),
                ScriptProjectFile(
                    "nested/b.kt",
                    "package sample.broken\n\nfun explode() = error(\"boom\")"
                )
            )
        )
        val generated = project.module.files.single { file ->
            file.text.contains("fun explode() = error(\"boom\")")
        }
        val generatedLine = generated.text
            .lines()
            .indexOf("fun explode() = error(\"boom\")") + 1
        val scriptFailure = IllegalStateException("boom").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "sample.broken.Generated",
                    "explode",
                    generated.name,
                    generatedLine
                )
            )
        }
        val wrapped = RuntimeException("wrapped", scriptFailure)

        assertEquals(
            ScriptProjectPosition("nested/b.kt", 3, 1),
            project.runtimePosition(wrapped)
        )

        project.remapRuntimeStackTrace(wrapped)
        assertEquals("nested/b.kt", scriptFailure.stackTrace.single().fileName)
        assertEquals(3, scriptFailure.stackTrace.single().lineNumber)
    }

    @Test
    fun `runtime failures with duplicate basenames use the facade class`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "alpha/common.kt",
                    "package sample.alpha\n\nfun alphaFailure() = error(\"alpha\")"
                ),
                ScriptProjectFile(
                    "beta/common.kt",
                    "package sample.beta\n\nfun betaFailure() = error(\"beta\")"
                )
            )
        )
        val beta = project.module.files.single { file ->
            file.name == "beta/common.kt"
        }
        val failure = IllegalStateException("beta").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "${beta.facadeClassName}\$betaFailure\$1",
                    "invoke",
                    "common.kt",
                    3
                )
            )
        }

        assertEquals(
            ScriptProjectPosition("beta/common.kt", 3, 1),
            project.runtimePosition(failure)
        )

        project.remapRuntimeStackTrace(failure)
        assertEquals("beta/common.kt", failure.stackTrace.single().fileName)
    }
}
