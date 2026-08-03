package eternalScript.core.workspace

import java.nio.file.Path

internal enum class WorkspaceState {
    READY,
    ACTION_REQUIRED,
    ERROR
}

internal data class WorkspaceStatus(
    val workspaceRoot: Path,
    val schemaVersion: Int,
    val templateVersion: String,
    val activePluginCount: Int,
    val classpathEntryCount: Int,
    val conflictCount: Int,
    val state: WorkspaceState,
    val lastError: String?,
    val ideRefreshRecommended: Boolean = false
)

internal data class WorkspaceUpdateResult(
    val status: WorkspaceStatus,
    val createdFiles: List<String> = emptyList(),
    val updatedFiles: List<String> = emptyList(),
    val conflictFiles: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val ideRefreshRecommended: Boolean = false
) {
    val successful: Boolean
        get() = errors.isEmpty() && status.state != WorkspaceState.ERROR
}
