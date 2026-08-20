package eternalscript.intellij.diagnostics

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import eternalscript.intellij.EternalScriptBundle
import eternalscript.intellij.model.EternalScriptEnvironmentProblem

internal class EternalScriptProblemReporter(private val project: Project) {
    private val log = Logger.getInstance(EternalScriptProblemReporter::class.java)
    private val activeKeys = linkedSetOf<String>()

    @Synchronized
    fun publish(problems: Collection<EternalScriptEnvironmentProblem>) {
        val next = problems.associateBy(::key)
        val newProblems = next.filterKeys { problemKey -> problemKey !in activeKeys }.values
        activeKeys.clear()
        activeKeys += next.keys
        newProblems.forEach(::notify)
    }

    fun reportMissing(path: String) {
        val key = "missing:$path"
        synchronized(this) {
            if (!activeKeys.add(key)) return
        }
        notify(EternalScriptBundle.message("environment.missing", path))
    }

    fun reportAnalysisFailure(reason: String, error: Throwable? = null) {
        val key = "analysis:$reason"
        synchronized(this) {
            if (!activeKeys.add(key)) return
        }
        if (error != null) log.warn(reason, error) else log.warn(reason)
        notify(EternalScriptBundle.message("analysis.failed", reason))
    }

    private fun notify(problem: EternalScriptEnvironmentProblem) {
        val content = when (problem) {
            is EternalScriptEnvironmentProblem.Missing -> EternalScriptBundle.message("environment.missing", "")
            is EternalScriptEnvironmentProblem.Invalid -> EternalScriptBundle.message(
                "environment.invalid",
                problem.reason
            )
            is EternalScriptEnvironmentProblem.Incompatible -> EternalScriptBundle.message(
                "environment.incompatible",
                problem.actual,
                problem.expected
            )
            is EternalScriptEnvironmentProblem.Untrusted -> EternalScriptBundle.message("environment.untrusted")
            is EternalScriptEnvironmentProblem.UnsafeScriptRoot -> EternalScriptBundle.message(
                "environment.unsafeRoot",
                problem.root
            )
            is EternalScriptEnvironmentProblem.MissingClasspath -> EternalScriptBundle.message(
                "environment.missingClasspath",
                problem.paths.joinToString()
            )
            is EternalScriptEnvironmentProblem.IncompatibleKotlin -> EternalScriptBundle.message(
                "environment.incompatibleKotlin",
                problem.actual,
                problem.expected
            )
            is EternalScriptEnvironmentProblem.AnalysisUnstable -> EternalScriptBundle.message(
                "analysis.unstable",
                problem.sourceUrls.joinToString()
            )
        }
        notify(content)
    }

    private fun notify(content: String) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup("EternalScript")
            .createNotification("EternalScript", content, NotificationType.WARNING)
            .notify(project)
    }

    private fun key(problem: EternalScriptEnvironmentProblem): String = problem.toString()
}
