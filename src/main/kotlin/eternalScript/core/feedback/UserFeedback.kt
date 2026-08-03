package eternalScript.core.feedback

import eternalScript.core.manager.ScriptProjectStatus
import eternalScript.core.operation.ScriptOperationKind
import eternalScript.core.script.generation.ScriptLifecycleFailureSummary
import eternalScript.core.script.generation.ScriptProjectCheckResult
import eternalScript.core.script.generation.ScriptProjectDiagnosticSummary
import eternalScript.core.script.generation.ScriptProjectLoadResult
import eternalScript.core.script.generation.ScriptProjectUnloadResult
import eternalScript.core.workspace.WorkspaceStatus
import eternalScript.core.workspace.WorkspaceUpdateResult

/** Presentation-neutral channel used by application and command layers. */
internal fun interface UserFeedback {
    fun emit(event: UserFeedbackEvent)
}

/** Semantic events. Locale keys and Bukkit delivery are intentionally absent. */
internal sealed interface UserFeedbackEvent {
    data class ProjectSourceMissing(val activeProject: Boolean) : UserFeedbackEvent
    data class ProjectReloadStarted(val sourceCount: Int) : UserFeedbackEvent
    data class ProjectReloadFinished(val result: ScriptProjectLoadResult) : UserFeedbackEvent
    data class ProjectCheckStarted(val sourceCount: Int) : UserFeedbackEvent
    data class ProjectCheckFinished(
        val sourceCount: Int,
        val result: ScriptProjectCheckResult
    ) : UserFeedbackEvent

    data class ProjectUnloadStarted(
        val sourceCount: Int,
        val entryCount: Int
    ) : UserFeedbackEvent

    data class ProjectUnloadFinished(
        val result: ScriptProjectUnloadResult,
        val diskSourceCount: Int
    ) : UserFeedbackEvent

    data object ConfigurationReloadStarted : UserFeedbackEvent
    data class ConfigurationReloadFinished(
        val workspace: WorkspaceUpdateResult
    ) : UserFeedbackEvent

    data object WorkspaceUpdateStarted : UserFeedbackEvent
    data class WorkspaceUpdateFinished(
        val update: WorkspaceUpdateResult
    ) : UserFeedbackEvent

    data object CacheClearStarted : UserFeedbackEvent
    data object CacheClearFinished : UserFeedbackEvent

    data class OperationFailed(
        val kind: ScriptOperationKind,
        val incidentId: String
    ) : UserFeedbackEvent

    data object OperationBusy : UserFeedbackEvent
    data object EnvironmentPreparing : UserFeedbackEvent

    data class ProjectEntries(
        val entries: List<String>,
        val diskSourceCount: Int,
        val activeProject: Boolean
    ) : UserFeedbackEvent

    data class ProjectStatusView(
        val project: ScriptProjectStatus,
        val workspace: WorkspaceStatus
    ) : UserFeedbackEvent

    data class WorkspaceStatusView(val workspace: WorkspaceStatus) : UserFeedbackEvent

    data class WorkspaceMaintenance(val update: WorkspaceUpdateResult) : UserFeedbackEvent

    data class StartupSummary(
        val workspace: WorkspaceUpdateResult,
        val sourceCount: Int,
        val loadResult: ScriptProjectLoadResult?
    ) : UserFeedbackEvent
}

internal enum class UserFeedbackStage {
    START,
    DETAIL,
    RESULT,
    NEXT_ACTION
}

internal enum class UserFeedbackSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

internal sealed interface UserFeedbackArgument {
    data class Text(val value: String) : UserFeedbackArgument
    data class Translation(val key: String) : UserFeedbackArgument
    data class Quoted(val value: String) : UserFeedbackArgument
}

/** Locale-ready output selected only by the presenter layer. */
internal data class UserFeedbackMessage(
    val key: String,
    val arguments: List<UserFeedbackArgument> = emptyList(),
    val stage: UserFeedbackStage,
    val severity: UserFeedbackSeverity,
    val internalFailure: Boolean = false
)

internal fun ScriptProjectLoadResult.feedbackDetails(): List<UserFeedbackEventDetail> =
    report.feedbackDetails()

internal fun ScriptProjectCheckResult.feedbackDetails(): List<UserFeedbackEventDetail> =
    report.feedbackDetails()

internal fun ScriptProjectUnloadResult.feedbackDetails(): List<UserFeedbackEventDetail> =
    report.feedbackDetails()

internal sealed interface UserFeedbackEventDetail {
    data class Diagnostic(
        val value: ScriptProjectDiagnosticSummary
    ) : UserFeedbackEventDetail

    data class LifecycleFailure(
        val value: ScriptLifecycleFailureSummary
    ) : UserFeedbackEventDetail
}

private fun eternalScript.core.script.generation.ScriptProjectReport.feedbackDetails() =
    diagnostics.map(UserFeedbackEventDetail::Diagnostic) +
        lifecycleFailures.map(UserFeedbackEventDetail::LifecycleFailure)
