package eternalScript.core.feedback

import eternalScript.core.manager.AutomaticProjectLoadState
import eternalScript.core.manager.ScriptProjectStatus
import eternalScript.core.operation.ScriptOperation
import eternalScript.core.operation.ScriptOperationKind
import eternalScript.core.operation.ScriptOperationSnapshot
import eternalScript.core.operation.ScriptOperationState
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.generation.GenerationDiagnosticPhase
import eternalScript.core.script.generation.ScriptLifecycleFailurePhase
import eternalScript.core.script.generation.ScriptLifecycleFailureSummary
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UserFeedbackLocaleCoverageTest {
    @Test
    fun `semantic feedback variants render completely in every bundled locale`() {
        val catalogs = LANGUAGES.associateWith(::loadCatalog)
        val english = catalogs.getValue("en_US")
        val localeKeys = english.keys - "_schema"
        val renderer = UserFeedbackTextRenderer { key, language ->
            catalogs.getValue(language)[key]?.jsonPrimitive?.content
                ?: english.getValue(key).jsonPrimitive.content
        }
        val events = allEvents()

        assertTrue(events.isNotEmpty())
        LANGUAGES.forEach { language ->
            events.forEach { event ->
                UserFeedbackPresenter.present(event).forEach { message ->
                    val rendered = renderer.render(message, language)
                    assertNotEquals(message.key, rendered, "$language:${message.key}")
                    assertFalse("%s" in rendered, "$language:${message.key}:$rendered")
                    localeKeys.forEach { localeKey ->
                        assertFalse(
                            localeKey in rendered,
                            "$language:${message.key} leaked locale key $localeKey"
                        )
                    }
                }
            }
        }
    }

    private fun allEvents(): List<UserFeedbackEvent> = buildList {
        add(UserFeedbackEvent.ProjectSourceMissing(activeProject = false))
        add(UserFeedbackEvent.ProjectSourceMissing(activeProject = true))
        add(UserFeedbackEvent.ProjectReloadStarted(sourceCount = 2))
        ScriptProjectLoadOutcome.entries.forEach { outcome ->
            add(UserFeedbackEvent.ProjectReloadFinished(loadResult(outcome)))
            add(
                UserFeedbackEvent.StartupSummary(
                    workspace = workspaceUpdate(WorkspaceState.READY),
                    sourceCount = 2,
                    loadResult = loadResult(outcome)
                )
            )
        }
        add(UserFeedbackEvent.ProjectCheckStarted(sourceCount = 2))
        ScriptProjectCheckOutcome.entries.forEach { outcome ->
            add(
                UserFeedbackEvent.ProjectCheckFinished(
                    sourceCount = if (outcome == ScriptProjectCheckOutcome.NO_SOURCES) 0 else 2,
                    result = ScriptProjectCheckResult(
                        outcome = outcome,
                        report = if (outcome == ScriptProjectCheckOutcome.FAILED) report() else ScriptProjectReport()
                    )
                )
            )
        }
        add(UserFeedbackEvent.ProjectUnloadStarted(sourceCount = 2, entryCount = 1))
        ScriptProjectUnloadOutcome.entries.forEach { outcome ->
            add(
                UserFeedbackEvent.ProjectUnloadFinished(
                    result = ScriptProjectUnloadResult(
                        outcome = outcome,
                        sourceCount = 2,
                        entryCount = 1,
                        generation = if (outcome == ScriptProjectUnloadOutcome.UNLOADED) {
                            ScriptProjectGenerationSnapshot.EMPTY
                        } else {
                            generation(ScriptExecutionGate.State.ACTIVE)
                        },
                        report = if (outcome == ScriptProjectUnloadOutcome.REJECTED) report() else ScriptProjectReport()
                    ),
                    diskSourceCount = 2
                )
            )
        }
        add(UserFeedbackEvent.ConfigurationReloadStarted)
        add(UserFeedbackEvent.ConfigurationReloadFinished(workspaceUpdate(WorkspaceState.READY)))
        add(UserFeedbackEvent.ConfigurationReloadFinished(workspaceUpdate(WorkspaceState.ERROR)))
        add(UserFeedbackEvent.WorkspaceUpdateStarted)
        WorkspaceState.entries.forEach { state ->
            add(UserFeedbackEvent.WorkspaceUpdateFinished(workspaceUpdate(state)))
            add(UserFeedbackEvent.WorkspaceStatusView(workspace(state, ideRefresh = true)))
        }
        add(UserFeedbackEvent.CacheClearStarted)
        add(UserFeedbackEvent.CacheClearFinished)
        ScriptOperationKind.entries.forEach { kind ->
            add(UserFeedbackEvent.OperationFailed(kind, "abcd1234"))
        }
        add(UserFeedbackEvent.OperationBusy)
        add(UserFeedbackEvent.EnvironmentPreparing)
        add(UserFeedbackEvent.ProjectEntries(emptyList(), diskSourceCount = 0, activeProject = false))
        add(
            UserFeedbackEvent.ProjectEntries(
                listOf("sample.B", "sample.A"),
                diskSourceCount = 2,
                activeProject = true
            )
        )
        add(UserFeedbackEvent.WorkspaceMaintenance(workspaceUpdate(WorkspaceState.ACTION_REQUIRED)))
        add(
            UserFeedbackEvent.StartupSummary(
                workspace = workspaceUpdate(WorkspaceState.ERROR),
                sourceCount = 0,
                loadResult = null
            )
        )

        val generationStates = listOf<ScriptExecutionGate.State?>(null) +
            ScriptExecutionGate.State.entries
        generationStates.forEach { state ->
            add(
                UserFeedbackEvent.ProjectStatusView(
                    projectStatus(state = state),
                    workspace(WorkspaceState.READY)
                )
            )
        }
        ScriptOperationKind.entries.forEach { kind ->
            ScriptOperationState.entries.forEach { state ->
                add(
                    UserFeedbackEvent.ProjectStatusView(
                        projectStatus(
                            state = ScriptExecutionGate.State.ACTIVE,
                            operation = ScriptOperationSnapshot(ScriptOperation(kind), state)
                        ),
                        workspace(WorkspaceState.READY)
                    )
                )
            }
        }
    }

    private fun report() = ScriptProjectReport(
        diagnostics = GenerationDiagnosticPhase.entries.map { phase ->
            ScriptProjectDiagnosticSummary(
                phase = phase,
                sourceName = "sample.kt",
                line = 4,
                column = 2,
                message = "diagnostic"
            )
        },
        lifecycleFailures = ScriptLifecycleFailurePhase.entries.map { phase ->
            ScriptLifecycleFailureSummary(
                phase = phase,
                sourceName = "sample.kt",
                line = 7,
                reason = "lifecycle failure"
            )
        }
    )

    private fun loadResult(outcome: ScriptProjectLoadOutcome) = ScriptProjectLoadResult(
        outcome = outcome,
        previousGeneration = if (
            outcome == ScriptProjectLoadOutcome.REJECTED_PREVIOUS_ACTIVE ||
            outcome == ScriptProjectLoadOutcome.REJECTED_ACTIVE_CHANGED
        ) generation(ScriptExecutionGate.State.ACTIVE) else ScriptProjectGenerationSnapshot.EMPTY,
        generation = if (outcome == ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE) {
            ScriptProjectGenerationSnapshot.EMPTY
        } else {
            generation(ScriptExecutionGate.State.ACTIVE)
        },
        report = if (outcome == ScriptProjectLoadOutcome.ACTIVATED) ScriptProjectReport() else report()
    )

    private fun projectStatus(
        state: ScriptExecutionGate.State?,
        operation: ScriptOperationSnapshot? = null
    ) = ScriptProjectStatus(
        generation = if (state == null) ScriptProjectGenerationSnapshot.EMPTY else generation(state),
        availableSources = setOf("sample.kt"),
        currentUserOperation = operation,
        lastUserOperation = operation,
        backgroundMaintenance = false,
        automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
    )

    private fun generation(state: ScriptExecutionGate.State) = ScriptProjectGenerationSnapshot(
        state = state,
        sourceNames = setOf("sample.kt", "shared.kt"),
        entryNames = listOf("sample.Entry")
    )

    private fun workspace(
        state: WorkspaceState,
        ideRefresh: Boolean = false
    ) = WorkspaceStatus(
        workspaceRoot = Path.of("plugins", "EternalScript"),
        schemaVersion = 1,
        templateVersion = "test",
        activePluginCount = 2,
        classpathEntryCount = 3,
        conflictCount = if (state == WorkspaceState.ACTION_REQUIRED) 1 else 0,
        state = state,
        lastError = if (state == WorkspaceState.ERROR) "workspace error" else null,
        ideRefreshRecommended = ideRefresh
    )

    private fun workspaceUpdate(state: WorkspaceState) = WorkspaceUpdateResult(
        status = workspace(state, ideRefresh = true),
        createdFiles = listOf("created.file"),
        updatedFiles = listOf("updated.file"),
        conflictFiles = if (state == WorkspaceState.ACTION_REQUIRED) listOf("conflict.file") else emptyList(),
        errors = if (state == WorkspaceState.ERROR) listOf("workspace error") else emptyList(),
        ideRefreshRecommended = true
    )

    private fun loadCatalog(language: String): JsonObject {
        val resource = requireNotNull(javaClass.getResourceAsStream("/lang/$language.json"))
        return resource.bufferedReader(Charsets.UTF_8).use { reader ->
            Json.decodeFromString(reader.readText())
        }
    }

    private companion object {
        val LANGUAGES = listOf("en_US", "ko_KR", "ja_JP", "zh_CN")
    }
}
