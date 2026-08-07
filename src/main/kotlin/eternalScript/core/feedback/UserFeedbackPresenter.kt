package eternalScript.core.feedback

import eternalScript.core.feedback.UserFeedbackArgument.Quoted
import eternalScript.core.feedback.UserFeedbackArgument.Text
import eternalScript.core.feedback.UserFeedbackArgument.Translation
import eternalScript.core.feedback.UserFeedbackEvent.CacheClearFinished
import eternalScript.core.feedback.UserFeedbackEvent.CacheClearStarted
import eternalScript.core.feedback.UserFeedbackEvent.ConfigurationReloadFinished
import eternalScript.core.feedback.UserFeedbackEvent.ConfigurationReloadStarted
import eternalScript.core.feedback.UserFeedbackEvent.EnvironmentPreparing
import eternalScript.core.feedback.UserFeedbackEvent.OperationBusy
import eternalScript.core.feedback.UserFeedbackEvent.OperationFailed
import eternalScript.core.feedback.UserFeedbackEvent.ProjectCheckFinished
import eternalScript.core.feedback.UserFeedbackEvent.ProjectCheckStarted
import eternalScript.core.feedback.UserFeedbackEvent.ProjectEntries
import eternalScript.core.feedback.UserFeedbackEvent.ProjectReloadFinished
import eternalScript.core.feedback.UserFeedbackEvent.ProjectReloadStarted
import eternalScript.core.feedback.UserFeedbackEvent.ProjectSourceMissing
import eternalScript.core.feedback.UserFeedbackEvent.ProjectStatusView
import eternalScript.core.feedback.UserFeedbackEvent.ProjectUnloadFinished
import eternalScript.core.feedback.UserFeedbackEvent.ProjectUnloadStarted
import eternalScript.core.feedback.UserFeedbackEvent.StartupSummary
import eternalScript.core.feedback.UserFeedbackEvent.WorkspaceMaintenance
import eternalScript.core.feedback.UserFeedbackEvent.WorkspaceStatusView
import eternalScript.core.feedback.UserFeedbackEvent.WorkspaceUpdateFinished
import eternalScript.core.feedback.UserFeedbackEvent.WorkspaceUpdateStarted
import eternalScript.core.manager.AutomaticProjectLoadState
import eternalScript.core.manager.ScriptProjectState
import eternalScript.core.operation.ScriptOperationKind
import eternalScript.core.operation.ScriptOperationSnapshot
import eternalScript.core.operation.ScriptOperationState
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.generation.GenerationDiagnosticPhase
import eternalScript.core.script.generation.ScriptLifecycleFailurePhase
import eternalScript.core.script.generation.ScriptProjectLoadOutcome
import eternalScript.core.script.generation.ScriptProjectCheckOutcome
import eternalScript.core.script.generation.ScriptProjectUnloadOutcome
import eternalScript.core.workspace.WorkspaceState
import eternalScript.core.workspace.WorkspaceStatus
import eternalScript.core.workspace.WorkspaceUpdateResult

/** Pure mapping from semantic events to locale-ready message descriptions. */
internal object UserFeedbackPresenter {
    fun present(
        event: UserFeedbackEvent,
        detailLimit: Int? = null
    ): List<UserFeedbackMessage> {
        require(detailLimit == null || detailLimit >= 0)
        val messages = when (event) {
            is ProjectSourceMissing -> projectSourceMissing(event)
            is ProjectReloadStarted -> listOf(start("script.reload.started", event.sourceCount))
            is ProjectReloadFinished -> reloadFinished(event)
            is ProjectCheckStarted -> listOf(start("script.check.started", event.sourceCount))
            is ProjectCheckFinished -> checkFinished(event)
            is ProjectUnloadStarted -> listOf(
                start("script.unload.started", event.sourceCount, event.entryCount)
            )
            is ProjectUnloadFinished -> unloadFinished(event)
            ConfigurationReloadStarted -> listOf(start("config.reload.started"))
            is ConfigurationReloadFinished -> configurationReloadFinished(event.workspace)
            WorkspaceUpdateStarted -> listOf(start("workspace.update.started"))
            is WorkspaceUpdateFinished -> workspaceUpdateFinished(event.update)
            CacheClearStarted -> listOf(start("cache.clear.started"))
            CacheClearFinished -> listOf(
                result("cache.clear.completed", UserFeedbackSeverity.SUCCESS),
                next("feedback.next.check")
            )
            is OperationFailed -> listOf(
                message(
                    key = "script.operation.failed",
                    stage = UserFeedbackStage.RESULT,
                    severity = UserFeedbackSeverity.ERROR,
                    arguments = listOf(
                        Translation(operationKindKey(event.kind)),
                        Text(event.incidentId)
                    ),
                    internalFailure = true
                ),
                next("feedback.next.status")
            )
            OperationBusy -> listOf(
                result("script.operation.busy", UserFeedbackSeverity.WARNING),
                next("feedback.next.wait")
            )
            EnvironmentPreparing -> listOf(
                result("script.environment.preparing", UserFeedbackSeverity.WARNING),
                next("feedback.next.wait")
            )
            is ProjectEntries -> projectEntries(event)
            is ProjectStatusView -> projectStatus(event)
            is WorkspaceStatusView -> workspaceStatus(event.workspace)
            is WorkspaceMaintenance -> workspaceMaintenance(event.update)
            is StartupSummary -> startupSummary(event)
        }
        return limitDetails(messages, detailLimit)
    }

    private fun projectSourceMissing(event: ProjectSourceMissing) = listOf(
        result("script.error.empty_project", UserFeedbackSeverity.WARNING),
        next(
            if (event.activeProject) {
                "feedback.next.source_or_unload"
            } else {
                "feedback.next.example"
            }
        )
    )

    private fun reloadFinished(event: ProjectReloadFinished): List<UserFeedbackMessage> {
        val result = event.result
        val output = mutableListOf<UserFeedbackMessage>()
        output += reportDetails(result.feedbackDetails())
        when (result.outcome) {
            ScriptProjectLoadOutcome.ACTIVATED -> {
                output += result(
                    "script.reload.completed",
                    UserFeedbackSeverity.SUCCESS,
                    result.generation.sourceNames.size,
                    result.generation.entryNames.size
                )
                output += next("feedback.next.edit")
            }
            ScriptProjectLoadOutcome.REJECTED_PREVIOUS_ACTIVE -> {
                output += result(
                    "script.reload.failed_preserved",
                    UserFeedbackSeverity.WARNING,
                    result.generation.sourceNames.size,
                    result.generation.entryNames.size
                )
                output += next("feedback.next.check")
            }
            ScriptProjectLoadOutcome.REJECTED_ACTIVE_CHANGED -> {
                output += result("script.reload.failed_active", UserFeedbackSeverity.WARNING)
                output += next("feedback.next.status")
            }
            ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE -> {
                if (result.generation.exists) {
                    output += result(
                        "script.reload.cleanup_pending",
                        UserFeedbackSeverity.WARNING,
                        result.generation.sourceNames.size,
                        result.generation.entryNames.size
                    )
                    output += next("feedback.next.cleanup")
                } else {
                    output += result("script.reload.failed_inactive", UserFeedbackSeverity.ERROR)
                    output += next("feedback.next.check")
                }
            }
        }
        return output
    }

    private fun checkFinished(event: ProjectCheckFinished): List<UserFeedbackMessage> {
        val output = mutableListOf<UserFeedbackMessage>()
        output += reportDetails(event.result.feedbackDetails())
        when (event.result.outcome) {
            ScriptProjectCheckOutcome.NO_SOURCES -> {
                output += result("script.error.empty_project", UserFeedbackSeverity.WARNING)
                output += next("feedback.next.example")
            }
            ScriptProjectCheckOutcome.PASSED -> {
                output += result(
                    "script.check.passed",
                    UserFeedbackSeverity.SUCCESS,
                    event.sourceCount,
                    event.result.diagnosticCount
                )
                output += next("feedback.next.reload")
            }
            ScriptProjectCheckOutcome.FAILED -> {
                output += result(
                    "script.check.failed",
                    UserFeedbackSeverity.ERROR,
                    event.sourceCount,
                    event.result.diagnosticCount
                )
                output += next("feedback.next.check")
            }
        }
        return output
    }

    private fun unloadFinished(event: ProjectUnloadFinished): List<UserFeedbackMessage> {
        val output = mutableListOf<UserFeedbackMessage>()
        output += reportDetails(event.result.feedbackDetails())
        when (event.result.outcome) {
            ScriptProjectUnloadOutcome.UNLOADED -> output += result(
                "script.unload.completed",
                UserFeedbackSeverity.SUCCESS,
                event.result.sourceCount,
                event.result.entryCount
            )
            ScriptProjectUnloadOutcome.ALREADY_EMPTY -> output += result(
                "script.unload.empty",
                UserFeedbackSeverity.INFO
            )
            ScriptProjectUnloadOutcome.REJECTED -> output += result(
                "script.unload.rejected",
                UserFeedbackSeverity.WARNING
            )
        }
        output += next(
            when {
                event.result.outcome == ScriptProjectUnloadOutcome.REJECTED &&
                    event.result.generation.exists &&
                    !event.result.generation.acceptsCallbacks ->
                    "feedback.next.cleanup"
                event.result.outcome == ScriptProjectUnloadOutcome.REJECTED ->
                    "feedback.next.wait"
                event.diskSourceCount == 0 -> "feedback.next.example"
                else -> "feedback.next.reload"
            }
        )
        return output
    }

    private fun configurationReloadFinished(
        workspace: WorkspaceUpdateResult
    ): List<UserFeedbackMessage> {
        val output = mutableListOf<UserFeedbackMessage>()
        output += workspaceDetails(workspace)
        output += if (workspace.successful) {
            result(
                "config.reload.completed",
                if (workspace.conflictFiles.isEmpty()) {
                    UserFeedbackSeverity.SUCCESS
                } else {
                    UserFeedbackSeverity.WARNING
                },
                Translation(workspaceStateKey(workspace.status.state)),
                Text(workspace.conflictFiles.size.toString()),
                Text(workspace.errors.size.toString())
            )
        } else {
            result(
                "config.reload.partial",
                UserFeedbackSeverity.ERROR,
                workspace.errors.size
            )
        }
        output += workspaceNext(workspace)
        return output
    }

    private fun workspaceUpdateFinished(
        update: WorkspaceUpdateResult
    ): List<UserFeedbackMessage> {
        val severity = when {
            !update.successful -> UserFeedbackSeverity.ERROR
            update.conflictFiles.isNotEmpty() -> UserFeedbackSeverity.WARNING
            else -> UserFeedbackSeverity.SUCCESS
        }
        val key = if (update.successful) {
            "workspace.update.completed"
        } else {
            "workspace.update.failed"
        }
        val output = mutableListOf<UserFeedbackMessage>()
        output += workspaceDetails(update)
        output += result(
            key,
            severity,
            update.createdFiles.size,
            update.updatedFiles.size,
            update.conflictFiles.size,
            update.errors.size
        )
        output += workspaceNext(update)
        return output
    }

    private fun projectEntries(event: ProjectEntries): List<UserFeedbackMessage> {
        if (event.entries.isEmpty()) {
            return listOf(
                result("script.list.empty", UserFeedbackSeverity.INFO),
                next(
                    when {
                        event.diskSourceCount == 0 -> "feedback.next.example"
                        event.activeProject -> "feedback.next.edit"
                        else -> "feedback.next.reload"
                    }
                )
            )
        }
        return buildList {
            add(result("script.list.header", UserFeedbackSeverity.INFO, event.entries.size))
            event.entries.sorted().forEach { entry ->
                add(detail("script.list.entry", UserFeedbackSeverity.INFO, Quoted(entry)))
            }
            add(next("feedback.next.edit"))
        }
    }

    private fun projectStatus(event: ProjectStatusView): List<UserFeedbackMessage> {
        val project = event.project
        val output = mutableListOf(
            message(
                key = "script.status",
                stage = UserFeedbackStage.RESULT,
                severity = UserFeedbackSeverity.INFO,
                arguments = listOf(
                    Translation(projectStateKey(project.state)),
                    Text(project.generation.sourceNames.size.toString()),
                    Text(project.availableSources.size.toString()),
                    Text(project.generation.entryNames.size.toString())
                )
            )
        )
        project.currentUserOperation?.let { operation ->
            output += operationDetail("script.status.current_operation", operation)
        } ?: project.lastUserOperation?.let { operation ->
            output += operationDetail("script.status.last_operation", operation)
        }
        if (project.backgroundMaintenance) {
            output += detail("script.status.background", UserFeedbackSeverity.INFO)
        }
        if (event.workspace.state != WorkspaceState.READY) {
            output += detail(
                "script.status.workspace_attention",
                UserFeedbackSeverity.WARNING,
                event.workspace.conflictCount
            )
        }
        if (event.workspace.ideRefreshRecommended) {
            output += detail(
                "workspace.status.ide_refresh",
                UserFeedbackSeverity.WARNING
            )
        }
        output += next(statusNextKey(project, event.workspace))
        return output
    }

    private fun workspaceStatus(workspace: WorkspaceStatus): List<UserFeedbackMessage> {
        val output = mutableListOf(
            message(
                key = "workspace.status",
                stage = UserFeedbackStage.RESULT,
                severity = when (workspace.state) {
                    WorkspaceState.READY -> UserFeedbackSeverity.SUCCESS
                    WorkspaceState.ACTION_REQUIRED -> UserFeedbackSeverity.WARNING
                    WorkspaceState.ERROR -> UserFeedbackSeverity.ERROR
                },
                arguments = listOf(
                    Quoted(workspace.workspaceRoot.toString()),
                    Translation(workspaceStateKey(workspace.state)),
                    Text(workspace.conflictCount.toString())
                )
            )
        )
        workspace.lastError?.takeIf(String::isNotBlank)?.let { error ->
            output += detail(
                "workspace.status.last_error",
                UserFeedbackSeverity.ERROR,
                Text(error)
            )
        }
        if (workspace.ideRefreshRecommended) {
            output += detail(
                "workspace.status.ide_refresh",
                UserFeedbackSeverity.WARNING
            )
        }
        output += next(
            when {
                workspace.state != WorkspaceState.READY ->
                    "feedback.next.workspace_update"
                workspace.ideRefreshRecommended -> "feedback.next.ide_refresh"
                else -> "feedback.next.status"
            }
        )
        return output
    }

    private fun workspaceMaintenance(update: WorkspaceUpdateResult): List<UserFeedbackMessage> {
        if (
            update.conflictFiles.isEmpty() &&
            update.errors.isEmpty() &&
            !update.ideRefreshRecommended
        ) {
            return emptyList()
        }
        return buildList {
            addAll(workspaceDetails(update))
            if (update.ideRefreshRecommended) {
                add(detail("workspace.update.ide_refresh", UserFeedbackSeverity.WARNING))
            }
        }
    }

    private fun startupSummary(event: StartupSummary): List<UserFeedbackMessage> {
        val output = mutableListOf<UserFeedbackMessage>()
        output += workspaceDetails(event.workspace)
        output += detail(
            "workspace.initialized",
            if (event.workspace.successful) {
                UserFeedbackSeverity.SUCCESS
            } else {
                UserFeedbackSeverity.ERROR
            },
            Quoted(event.workspace.status.workspaceRoot.toString()),
            Translation(workspaceStateKey(event.workspace.status.state))
        )

        val loadResult = event.loadResult
        if (loadResult == null) {
            output += result("script.automatic_load.empty", UserFeedbackSeverity.INFO)
        } else {
            output += reportDetails(loadResult.feedbackDetails())
            when (loadResult.outcome) {
                ScriptProjectLoadOutcome.ACTIVATED -> output += result(
                    "script.automatic_load.completed",
                    UserFeedbackSeverity.SUCCESS,
                    loadResult.generation.sourceNames.size,
                    loadResult.generation.entryNames.size
                )
                ScriptProjectLoadOutcome.REJECTED_PREVIOUS_ACTIVE,
                ScriptProjectLoadOutcome.REJECTED_ACTIVE_CHANGED -> output += result(
                    "script.automatic_load.failed_preserved",
                    UserFeedbackSeverity.WARNING,
                    loadResult.generation.sourceNames.size,
                    loadResult.generation.entryNames.size
                )
                ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE -> {
                    if (loadResult.generation.exists) {
                        output += result(
                            "script.reload.cleanup_pending",
                            UserFeedbackSeverity.WARNING,
                            loadResult.generation.sourceNames.size,
                            loadResult.generation.entryNames.size
                        )
                    } else {
                        output += result(
                            "script.automatic_load.failed_inactive",
                            UserFeedbackSeverity.ERROR,
                            event.sourceCount
                        )
                    }
                }
            }
        }
        output += next(
            when {
                event.workspace.status.state != WorkspaceState.READY ->
                    "feedback.next.workspace_update"
                loadResult == null -> "feedback.next.example"
                !loadResult.activated &&
                    loadResult.generation.exists &&
                    !loadResult.generation.acceptsCallbacks ->
                    "feedback.next.cleanup"
                !loadResult.activated -> "feedback.next.check"
                event.workspace.status.ideRefreshRecommended -> "feedback.next.ide_refresh"
                else -> "feedback.next.edit"
            }
        )
        return output
    }

    private fun reportDetails(
        details: List<UserFeedbackEventDetail>
    ): List<UserFeedbackMessage> = details.map { detail ->
        when (detail) {
            is UserFeedbackEventDetail.Diagnostic -> message(
                key = "script.diagnostic.error",
                stage = UserFeedbackStage.DETAIL,
                severity = UserFeedbackSeverity.ERROR,
                arguments = listOf(
                    Translation(diagnosticPhaseKey(detail.value.phase)),
                    Quoted(detail.value.sourceName),
                    Text(detail.value.line?.toString() ?: "-"),
                    Text(detail.value.column?.toString() ?: "-"),
                    Text(detail.value.message)
                )
            )
            is UserFeedbackEventDetail.LifecycleFailure -> message(
                key = "script.lifecycle.error",
                stage = UserFeedbackStage.DETAIL,
                severity = UserFeedbackSeverity.ERROR,
                arguments = listOf(
                    Quoted(detail.value.sourceName),
                    Text(detail.value.line?.toString() ?: "-"),
                    Translation(lifecyclePhaseKey(detail.value.phase)),
                    Text(detail.value.reason)
                )
            )
        }
    }

    private fun workspaceDetails(update: WorkspaceUpdateResult) = buildList {
        update.conflictFiles.forEach { path ->
            add(detail("workspace.update.conflict", UserFeedbackSeverity.WARNING, Quoted(path)))
        }
        update.errors.forEach { error ->
            add(detail("workspace.update.error", UserFeedbackSeverity.ERROR, Text(error)))
        }
    }

    private fun workspaceNext(update: WorkspaceUpdateResult) = next(
        when {
            update.errors.isNotEmpty() -> "feedback.next.workspace_review"
            update.conflictFiles.isNotEmpty() -> "feedback.next.workspace_conflicts"
            update.ideRefreshRecommended || update.status.ideRefreshRecommended ->
                "feedback.next.ide_refresh"
            else -> "feedback.next.status"
        }
    )

    private fun operationDetail(key: String, snapshot: ScriptOperationSnapshot) = message(
        key = key,
        stage = UserFeedbackStage.DETAIL,
        severity = if (snapshot.state == ScriptOperationState.FAILED) {
            UserFeedbackSeverity.WARNING
        } else {
            UserFeedbackSeverity.INFO
        },
        arguments = listOf(
            Translation(operationKindKey(snapshot.operation.kind)),
            Translation(operationStateKey(snapshot.state))
        )
    )

    private fun statusNextKey(
        project: eternalScript.core.manager.ScriptProjectStatus,
        workspace: WorkspaceStatus
    ): String = when {
        project.currentUserOperation != null ||
            project.backgroundMaintenance -> "feedback.next.wait"
        project.generation.state == ScriptExecutionGate.State.SWAPPING ->
            "feedback.next.cleanup"
        project.state == ScriptProjectState.UPDATING ||
            project.state == ScriptProjectState.STOPPING -> "feedback.next.wait"
        workspace.state != WorkspaceState.READY -> "feedback.next.workspace_update"
        project.automaticLoadState == AutomaticProjectLoadState.FAILED_INACTIVE ||
            project.automaticLoadState == AutomaticProjectLoadState.FAILED_PRESERVED ->
            "feedback.next.check"
        project.lastUserOperation?.state == ScriptOperationState.FAILED &&
            project.lastUserOperation.operation.kind in setOf(
                ScriptOperationKind.RELOAD,
                ScriptOperationKind.CHECK
            ) -> "feedback.next.check"
        project.availableSources.isEmpty() -> "feedback.next.example"
        workspace.ideRefreshRecommended -> "feedback.next.ide_refresh"
        project.state == ScriptProjectState.ACTIVE -> "feedback.next.edit"
        else -> "feedback.next.reload"
    }

    private fun operationKindKey(kind: ScriptOperationKind) = when (kind) {
        ScriptOperationKind.RELOAD -> "script.operation.scope.reload"
        ScriptOperationKind.CHECK -> "script.operation.scope.check"
        ScriptOperationKind.UNLOAD -> "script.operation.scope.unload"
        ScriptOperationKind.CONFIG_RELOAD -> "script.operation.scope.config_reload"
        ScriptOperationKind.WORKSPACE_UPDATE -> "script.operation.scope.workspace_update"
        ScriptOperationKind.CACHE_CLEAR -> "script.operation.scope.cache_clear"
        ScriptOperationKind.ENVIRONMENT_REFRESH -> "script.operation.scope.environment_refresh"
    }

    private fun operationStateKey(state: ScriptOperationState) = when (state) {
        ScriptOperationState.ACCEPTED -> "script.operation.state.accepted"
        ScriptOperationState.RUNNING -> "script.operation.state.running"
        ScriptOperationState.COMPLETED -> "script.operation.state.completed"
        ScriptOperationState.FAILED -> "script.operation.state.failed"
        ScriptOperationState.CANCELLED -> "script.operation.state.cancelled"
    }

    private fun projectStateKey(state: ScriptProjectState) = when (state) {
        ScriptProjectState.INACTIVE -> "script.project.state.inactive"
        ScriptProjectState.ACTIVE -> "script.project.state.active"
        ScriptProjectState.UPDATING -> "script.project.state.updating"
        ScriptProjectState.STOPPING -> "script.project.state.stopping"
    }

    private fun workspaceStateKey(state: WorkspaceState) = when (state) {
        WorkspaceState.READY -> "workspace.state.ready"
        WorkspaceState.ACTION_REQUIRED -> "workspace.state.action_required"
        WorkspaceState.ERROR -> "workspace.state.error"
    }

    private fun diagnosticPhaseKey(phase: GenerationDiagnosticPhase) = when (phase) {
        GenerationDiagnosticPhase.COMPILATION -> "script.diagnostic.phase.compilation"
        GenerationDiagnosticPhase.EVALUATION -> "script.diagnostic.phase.evaluation"
    }

    private fun lifecyclePhaseKey(phase: ScriptLifecycleFailurePhase) = when (phase) {
        ScriptLifecycleFailurePhase.ENABLE -> "script.lifecycle.phase.enable"
        ScriptLifecycleFailurePhase.DISABLE -> "script.lifecycle.phase.disable"
        ScriptLifecycleFailurePhase.PUBLISH -> "script.lifecycle.phase.publish"
        ScriptLifecycleFailurePhase.RESTORE -> "script.lifecycle.phase.restore"
        ScriptLifecycleFailurePhase.CLEANUP -> "script.lifecycle.phase.cleanup"
    }

    private fun start(key: String, vararg arguments: Any) = message(
        key,
        UserFeedbackStage.START,
        UserFeedbackSeverity.INFO,
        arguments.map(::argument)
    )

    private fun result(
        key: String,
        severity: UserFeedbackSeverity,
        vararg arguments: Any
    ) = message(key, UserFeedbackStage.RESULT, severity, arguments.map(::argument))

    private fun detail(
        key: String,
        severity: UserFeedbackSeverity,
        vararg arguments: Any
    ) = message(key, UserFeedbackStage.DETAIL, severity, arguments.map(::argument))

    private fun next(key: String) = message(
        key,
        UserFeedbackStage.NEXT_ACTION,
        UserFeedbackSeverity.INFO
    )

    private fun message(
        key: String,
        stage: UserFeedbackStage,
        severity: UserFeedbackSeverity,
        arguments: List<UserFeedbackArgument> = emptyList(),
        internalFailure: Boolean = false
    ) = UserFeedbackMessage(key, arguments, stage, severity, internalFailure)

    private fun argument(value: Any): UserFeedbackArgument = when (value) {
        is UserFeedbackArgument -> value
        else -> Text(value.toString())
    }

    private fun limitDetails(
        messages: List<UserFeedbackMessage>,
        limit: Int?
    ): List<UserFeedbackMessage> {
        if (limit == null) return messages
        val total = messages.count { it.stage == UserFeedbackStage.DETAIL }
        if (total <= limit) return messages

        val output = mutableListOf<UserFeedbackMessage>()
        var kept = 0
        var omitted = 0
        var truncationAdded = false
        fun addTruncation() {
            if (truncationAdded) return
            output += detail(
                "feedback.details.truncated",
                UserFeedbackSeverity.WARNING,
                kept,
                total
            )
            truncationAdded = true
        }

        messages.forEach { message ->
            if (message.stage == UserFeedbackStage.DETAIL) {
                if (kept < limit) {
                    output += message
                    kept += 1
                } else {
                    omitted += 1
                }
            } else {
                if (omitted > 0) {
                    addTruncation()
                }
                output += message
            }
        }
        addTruncation()
        return output
    }
}
