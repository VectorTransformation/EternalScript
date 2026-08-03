package eternalScript.core.feedback

import eternalScript.core.feedback.UserFeedbackArgument.Text
import eternalScript.core.feedback.UserFeedbackEvent.ConfigurationReloadFinished
import eternalScript.core.feedback.UserFeedbackEvent.ProjectCheckFinished
import eternalScript.core.feedback.UserFeedbackEvent.ProjectReloadFinished
import eternalScript.core.feedback.UserFeedbackEvent.ProjectStatusView
import eternalScript.core.feedback.UserFeedbackEvent.ProjectUnloadFinished
import eternalScript.core.feedback.UserFeedbackEvent.StartupSummary
import eternalScript.core.feedback.UserFeedbackEvent.WorkspaceUpdateFinished
import eternalScript.core.manager.AutomaticProjectLoadState
import eternalScript.core.manager.ScriptProjectStatus
import eternalScript.core.operation.ScriptOperation
import eternalScript.core.operation.ScriptOperationKind
import eternalScript.core.operation.ScriptOperationSnapshot
import eternalScript.core.operation.ScriptOperationState
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.generation.GenerationDiagnosticPhase
import eternalScript.core.script.generation.ScriptProjectCheckOutcome
import eternalScript.core.script.generation.ScriptProjectCheckResult
import eternalScript.core.script.generation.ScriptProjectDiagnosticSummary
import eternalScript.core.script.generation.ScriptProjectGenerationSnapshot
import eternalScript.core.script.generation.ScriptProjectLoadOutcome
import eternalScript.core.script.generation.ScriptProjectLoadResult
import eternalScript.core.script.generation.ScriptProjectReport
import eternalScript.core.script.generation.ScriptProjectUnloadOutcome
import eternalScript.core.script.generation.ScriptProjectUnloadResult
import eternalScript.core.workspace.WorkspaceState
import eternalScript.core.workspace.WorkspaceStatus
import eternalScript.core.workspace.WorkspaceUpdateResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserFeedbackPresenterTest {
    @Test
    fun `completion feedback keeps details before result and one final next action`() {
        val report = reportWithDiagnostics(1)
        val partialWorkspace = partialWorkspace()
        val events = listOf(
            "reload" to ProjectReloadFinished(
                loadResult(ScriptProjectLoadOutcome.REJECTED_PREVIOUS_ACTIVE, report)
            ),
            "check" to ProjectCheckFinished(
                sourceCount = 1,
                result = ScriptProjectCheckResult(
                    outcome = ScriptProjectCheckOutcome.FAILED,
                    report = report
                )
            ),
            "unload" to ProjectUnloadFinished(
                result = ScriptProjectUnloadResult(
                    outcome = ScriptProjectUnloadOutcome.REJECTED,
                    sourceCount = 1,
                    entryCount = 1,
                    generation = activeGeneration(),
                    report = report
                ),
                diskSourceCount = 1
            ),
            "configuration" to ConfigurationReloadFinished(partialWorkspace),
            "workspace" to WorkspaceUpdateFinished(partialWorkspace),
            "startup" to StartupSummary(
                workspace = partialWorkspace(),
                sourceCount = 1,
                loadResult = loadResult(ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE, report)
            )
        )

        events.forEach { (name, event) ->
            val messages = UserFeedbackPresenter.present(event)
            val detailIndices = messages.indices.filter { messages[it].stage == UserFeedbackStage.DETAIL }
            val resultIndex = messages.indexOfFirst { it.stage == UserFeedbackStage.RESULT }
            val nextIndices = messages.indices.filter {
                messages[it].stage == UserFeedbackStage.NEXT_ACTION
            }

            assertTrue(detailIndices.isNotEmpty(), name)
            assertTrue(resultIndex > checkNotNull(detailIndices.maxOrNull()), name)
            assertEquals(listOf(messages.lastIndex), nextIndices, name)
            assertTrue(resultIndex < nextIndices.single(), name)
        }
    }

    @Test
    fun `detail limit keeps a truncation detail before result and next action`() {
        val event = ProjectCheckFinished(
            sourceCount = 6,
            result = ScriptProjectCheckResult(
                outcome = ScriptProjectCheckOutcome.FAILED,
                report = reportWithDiagnostics(6)
            )
        )

        val messages = UserFeedbackPresenter.present(event, detailLimit = 2)

        assertEquals(
            listOf(
                UserFeedbackStage.DETAIL,
                UserFeedbackStage.DETAIL,
                UserFeedbackStage.DETAIL,
                UserFeedbackStage.RESULT,
                UserFeedbackStage.NEXT_ACTION
            ),
            messages.map(UserFeedbackMessage::stage)
        )
        assertEquals("feedback.details.truncated", messages[2].key)
        assertEquals(listOf(Text("2"), Text("6")), messages[2].arguments)
        assertEquals("script.check.failed", messages[3].key)
        assertEquals("feedback.next.check", messages.singleNextAction().key)

        assertFailsWith<IllegalArgumentException> {
            UserFeedbackPresenter.present(event, detailLimit = -1)
        }
    }

    @Test
    fun `status chooses one next action using user visible priority`() {
        val runningReload = ScriptOperationSnapshot(
            operation = ScriptOperation(ScriptOperationKind.RELOAD),
            state = ScriptOperationState.RUNNING
        )
        val failedCheck = ScriptOperationSnapshot(
            operation = ScriptOperation(ScriptOperationKind.CHECK),
            state = ScriptOperationState.FAILED
        )
        val cases = listOf(
            StatusCase(
                name = "current operation wins over every other condition",
                project = projectStatus(
                    currentOperation = runningReload,
                    automaticLoad = AutomaticProjectLoadState.FAILED_INACTIVE
                ),
                workspace = workspace(WorkspaceState.ERROR),
                expectedKey = "feedback.next.wait"
            ),
            StatusCase(
                name = "background maintenance wins over workspace attention",
                project = projectStatus(backgroundMaintenance = true),
                workspace = workspace(WorkspaceState.ACTION_REQUIRED),
                expectedKey = "feedback.next.wait"
            ),
            StatusCase(
                name = "generation transition wins over workspace attention",
                project = projectStatus(generationState = ScriptExecutionGate.State.STAGED),
                workspace = workspace(WorkspaceState.ACTION_REQUIRED),
                expectedKey = "feedback.next.wait"
            ),
            StatusCase(
                name = "stopping generation requires waiting",
                project = projectStatus(generationState = ScriptExecutionGate.State.RETIRED),
                workspace = workspace(),
                expectedKey = "feedback.next.wait"
            ),
            StatusCase(
                name = "workspace attention wins over automatic load failure",
                project = projectStatus(
                    availableSources = setOf("main.kt"),
                    automaticLoad = AutomaticProjectLoadState.FAILED_INACTIVE
                ),
                workspace = workspace(WorkspaceState.ACTION_REQUIRED),
                expectedKey = "feedback.next.workspace_update"
            ),
            StatusCase(
                name = "automatic load failure wins over IDE refresh advice",
                project = projectStatus(
                    availableSources = setOf("main.kt"),
                    automaticLoad = AutomaticProjectLoadState.FAILED_INACTIVE
                ),
                workspace = workspace(ideRefreshRecommended = true),
                expectedKey = "feedback.next.check"
            ),
            StatusCase(
                name = "automatic load failure wins over an empty source set",
                project = projectStatus(
                    automaticLoad = AutomaticProjectLoadState.FAILED_INACTIVE
                ),
                workspace = workspace(),
                expectedKey = "feedback.next.check"
            ),
            StatusCase(
                name = "failed automatic load remains actionable on an active project",
                project = projectStatus(
                    generationState = ScriptExecutionGate.State.ACTIVE,
                    availableSources = setOf("main.kt"),
                    automaticLoad = AutomaticProjectLoadState.FAILED_PRESERVED
                ),
                workspace = workspace(),
                expectedKey = "feedback.next.check"
            ),
            StatusCase(
                name = "failed check wins over an empty source set",
                project = projectStatus(lastOperation = failedCheck),
                workspace = workspace(),
                expectedKey = "feedback.next.check"
            ),
            StatusCase(
                name = "empty source set suggests an example",
                project = projectStatus(),
                workspace = workspace(),
                expectedKey = "feedback.next.example"
            ),
            StatusCase(
                name = "IDE refresh wins before normal editing",
                project = projectStatus(
                    generationState = ScriptExecutionGate.State.ACTIVE,
                    availableSources = setOf("main.kt")
                ),
                workspace = workspace(ideRefreshRecommended = true),
                expectedKey = "feedback.next.ide_refresh"
            ),
            StatusCase(
                name = "active project suggests editing",
                project = projectStatus(
                    generationState = ScriptExecutionGate.State.ACTIVE,
                    availableSources = setOf("main.kt")
                ),
                workspace = workspace(),
                expectedKey = "feedback.next.edit"
            ),
            StatusCase(
                name = "inactive project with sources suggests reload",
                project = projectStatus(availableSources = setOf("main.kt")),
                workspace = workspace(),
                expectedKey = "feedback.next.reload"
            )
        )

        cases.forEach { case ->
            val messages = UserFeedbackPresenter.present(
                ProjectStatusView(case.project, case.workspace)
            )

            assertEquals(case.expectedKey, messages.singleNextAction().key, case.name)
            assertEquals(UserFeedbackStage.NEXT_ACTION, messages.last().stage, case.name)
        }
    }

    @Test
    fun `partial workspace reports every detail before failed result and review action`() {
        val update = partialWorkspace(
            conflicts = listOf("build.gradle.kts", "settings.gradle.kts"),
            errors = listOf("cannot update wrapper")
        )

        val messages = UserFeedbackPresenter.present(WorkspaceUpdateFinished(update))

        assertEquals(
            listOf(
                "workspace.update.conflict",
                "workspace.update.conflict",
                "workspace.update.error",
                "workspace.update.failed",
                "feedback.next.workspace_review"
            ),
            messages.map(UserFeedbackMessage::key)
        )
        assertEquals(UserFeedbackSeverity.ERROR, messages[3].severity)
        assertEquals(
            listOf(Text("1"), Text("1"), Text("2"), Text("1")),
            messages[3].arguments
        )
        assertEquals("feedback.next.workspace_review", messages.singleNextAction().key)
    }

    @Test
    fun `automatic load outcomes expose one result and one useful next action`() {
        val cases = listOf(
            AutomaticLoadCase(
                outcome = ScriptProjectLoadOutcome.ACTIVATED,
                resultKey = "script.automatic_load.completed",
                severity = UserFeedbackSeverity.SUCCESS,
                nextKey = "feedback.next.edit",
                arguments = listOf(Text("2"), Text("2"))
            ),
            AutomaticLoadCase(
                outcome = ScriptProjectLoadOutcome.REJECTED_PREVIOUS_ACTIVE,
                resultKey = "script.automatic_load.failed_preserved",
                severity = UserFeedbackSeverity.WARNING,
                nextKey = "feedback.next.check",
                arguments = listOf(Text("2"), Text("2"))
            ),
            AutomaticLoadCase(
                outcome = ScriptProjectLoadOutcome.REJECTED_ACTIVE_CHANGED,
                resultKey = "script.automatic_load.failed_preserved",
                severity = UserFeedbackSeverity.WARNING,
                nextKey = "feedback.next.check",
                arguments = listOf(Text("2"), Text("2"))
            ),
            AutomaticLoadCase(
                outcome = ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE,
                resultKey = "script.automatic_load.failed_inactive",
                severity = UserFeedbackSeverity.ERROR,
                nextKey = "feedback.next.check",
                arguments = listOf(Text("4"))
            )
        )

        cases.forEach { case ->
            val messages = UserFeedbackPresenter.present(
                StartupSummary(
                    workspace = readyWorkspace(),
                    sourceCount = 4,
                    loadResult = loadResult(case.outcome)
                )
            )
            val result = messages.single { it.stage == UserFeedbackStage.RESULT }

            assertEquals(case.resultKey, result.key, case.outcome.name)
            assertEquals(case.severity, result.severity, case.outcome.name)
            assertEquals(case.arguments, result.arguments, case.outcome.name)
            assertEquals(case.nextKey, messages.singleNextAction().key, case.outcome.name)
        }

        val empty = UserFeedbackPresenter.present(
            StartupSummary(
                workspace = readyWorkspace(),
                sourceCount = 0,
                loadResult = null
            )
        )
        assertEquals("script.automatic_load.empty", empty.single {
            it.stage == UserFeedbackStage.RESULT
        }.key)
        assertEquals("feedback.next.example", empty.singleNextAction().key)
    }

    @Test
    fun `check with no sources reports the empty project and example action`() {
        val messages = UserFeedbackPresenter.present(
            ProjectCheckFinished(
                sourceCount = 0,
                result = ScriptProjectCheckResult(
                    outcome = ScriptProjectCheckOutcome.NO_SOURCES,
                    report = ScriptProjectReport()
                )
            )
        )

        assertEquals(
            listOf("script.error.empty_project", "feedback.next.example"),
            messages.map(UserFeedbackMessage::key)
        )
        assertEquals(UserFeedbackSeverity.WARNING, messages.first().severity)
        assertEquals("feedback.next.example", messages.singleNextAction().key)
    }

    private fun reportWithDiagnostics(count: Int) = ScriptProjectReport(
        diagnostics = (1..count).map { index ->
            ScriptProjectDiagnosticSummary(
                phase = GenerationDiagnosticPhase.COMPILATION,
                sourceName = "source-$index.kt",
                line = index,
                column = index + 1,
                message = "failure-$index"
            )
        }
    )

    private fun loadResult(
        outcome: ScriptProjectLoadOutcome,
        report: ScriptProjectReport = ScriptProjectReport()
    ): ScriptProjectLoadResult {
        val generation = when (outcome) {
            ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE -> ScriptProjectGenerationSnapshot.EMPTY
            else -> activeGeneration()
        }
        val previous = when (outcome) {
            ScriptProjectLoadOutcome.REJECTED_PREVIOUS_ACTIVE,
            ScriptProjectLoadOutcome.REJECTED_ACTIVE_CHANGED -> activeGeneration()
            ScriptProjectLoadOutcome.ACTIVATED,
            ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE -> ScriptProjectGenerationSnapshot.EMPTY
        }
        return ScriptProjectLoadResult(outcome, previous, generation, report)
    }

    private fun activeGeneration() = ScriptProjectGenerationSnapshot(
        state = ScriptExecutionGate.State.ACTIVE,
        sourceNames = setOf("main.kt", "shared.kt"),
        entryNames = listOf("sample.Alpha", "sample.Beta")
    )

    private fun projectStatus(
        generationState: ScriptExecutionGate.State? = null,
        availableSources: Set<String> = emptySet(),
        currentOperation: ScriptOperationSnapshot? = null,
        lastOperation: ScriptOperationSnapshot? = null,
        backgroundMaintenance: Boolean = false,
        automaticLoad: AutomaticProjectLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
    ) = ScriptProjectStatus(
        generation = ScriptProjectGenerationSnapshot(
            state = generationState,
            sourceNames = if (generationState == null) emptySet() else setOf("main.kt"),
            entryNames = if (generationState == null) emptyList() else listOf("sample.Main")
        ),
        availableSources = availableSources,
        currentUserOperation = currentOperation,
        lastUserOperation = lastOperation,
        backgroundMaintenance = backgroundMaintenance,
        automaticLoadState = automaticLoad
    )

    private fun workspace(
        state: WorkspaceState = WorkspaceState.READY,
        ideRefreshRecommended: Boolean = false
    ) = WorkspaceStatus(
        workspaceRoot = Path.of("plugins", "EternalScript"),
        schemaVersion = 3,
        templateVersion = "test",
        activePluginCount = 0,
        classpathEntryCount = 0,
        conflictCount = if (state == WorkspaceState.ACTION_REQUIRED) 1 else 0,
        state = state,
        lastError = if (state == WorkspaceState.ERROR) "workspace failed" else null,
        ideRefreshRecommended = ideRefreshRecommended
    )

    private fun partialWorkspace(
        conflicts: List<String> = listOf("build.gradle.kts"),
        errors: List<String> = listOf("workspace failed")
    ) = WorkspaceUpdateResult(
        status = WorkspaceStatus(
            workspaceRoot = Path.of("plugins", "EternalScript"),
            schemaVersion = 3,
            templateVersion = "test",
            activePluginCount = 1,
            classpathEntryCount = 2,
            conflictCount = conflicts.size,
            state = WorkspaceState.ERROR,
            lastError = errors.firstOrNull()
        ),
        createdFiles = listOf("WORKSPACE.md"),
        updatedFiles = listOf("gradlew.bat"),
        conflictFiles = conflicts,
        errors = errors,
        ideRefreshRecommended = true
    )

    private fun readyWorkspace() = WorkspaceUpdateResult(
        status = workspace(WorkspaceState.READY)
    )

    private fun List<UserFeedbackMessage>.singleNextAction() = single {
        it.stage == UserFeedbackStage.NEXT_ACTION
    }

    private data class StatusCase(
        val name: String,
        val project: ScriptProjectStatus,
        val workspace: WorkspaceStatus,
        val expectedKey: String
    )

    private data class AutomaticLoadCase(
        val outcome: ScriptProjectLoadOutcome,
        val resultKey: String,
        val severity: UserFeedbackSeverity,
        val nextKey: String,
        val arguments: List<UserFeedbackArgument>
    )
}
