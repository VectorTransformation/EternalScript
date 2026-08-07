package eternalScript.core.script.generation

import eternalScript.core.data.Config
import eternalScript.core.manager.ConfigManager
import eternalScript.core.script.project.PROJECT_SCRIPT_NAME
import eternalScript.core.script.project.ScriptProjectSource
import eternalScript.core.script.project.failureDiagnostics
import eternalScript.core.script.project.remapRuntimeStackTrace
import eternalScript.core.script.project.runtimePosition
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.script.experimental.api.ResultWithDiagnostics

/** Collects classloader-free diagnostic summaries without rendering user output. */
internal class GenerationDiagnostics(
    private val config: ConfigManager,
    private val logger: Logger
) {
    fun report(
        project: ScriptProjectSource,
        result: ResultWithDiagnostics<*>,
        phase: GenerationDiagnosticPhase,
        report: MutableScriptProjectReport
    ): Int {
        val errors = project.failureDiagnostics(result)
        errors.forEach { diagnostic ->
            report.diagnostics += ScriptProjectDiagnosticSummary(
                phase = phase,
                sourceName = diagnostic.sourceName,
                line = diagnostic.line,
                column = diagnostic.column,
                message = diagnostic.report.message
            )
            if (config.value<Boolean>(Config.DEBUG)) {
                diagnostic.report.exception?.let { exception ->
                    logger.log(
                        Level.WARNING,
                        "EternalScript ${phase.name.lowercase()} diagnostic at " +
                            "${diagnostic.sourceName}:${diagnostic.line ?: "-"}.",
                        exception
                    )
                }
            }
        }
        return errors.size
    }

    fun lifecycleFailure(
        project: ScriptProjectSource,
        phase: ScriptLifecycleFailurePhase,
        technicalPhase: String,
        exception: Throwable,
        report: MutableScriptProjectReport
    ) {
        val position = project.runtimePosition(exception)
        val sourceName = position?.sourceName ?: PROJECT_SCRIPT_NAME
        val line = position?.line
        project.remapRuntimeStackTrace(exception)
        val summary = ScriptLifecycleFailureSummary(
            phase = phase,
            sourceName = sourceName,
            line = line,
            reason = exception.message ?: exception.javaClass.simpleName
        )
        report.lifecycleFailures += summary

        if (report.logSummaries) {
            logger.warning(
                "EternalScript lifecycle failure during $technicalPhase at " +
                    "$sourceName:${line ?: "-"}: ${summary.reason}"
            )
        }
        if (config.value<Boolean>(Config.DEBUG)) {
            logger.log(
                Level.WARNING,
                "EternalScript lifecycle exception during $technicalPhase at " +
                    "$sourceName:${line ?: "-"}.",
                exception
            )
        }
    }
}

internal class MutableScriptProjectReport(
    val logSummaries: Boolean = false
) {
    val diagnostics = mutableListOf<ScriptProjectDiagnosticSummary>()
    val lifecycleFailures = mutableListOf<ScriptLifecycleFailureSummary>()

    fun snapshot() = ScriptProjectReport(
        diagnostics = diagnostics.toList(),
        lifecycleFailures = lifecycleFailures.toList()
    )
}
