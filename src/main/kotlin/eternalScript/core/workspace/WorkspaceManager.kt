package eternalScript.core.workspace

import java.nio.file.Path

/**
 * Owns the generated IntelliJ/Gradle workspace in the EternalScript data
 * folder. All filesystem failures are reported in [WorkspaceUpdateResult] so
 * workspace maintenance never has to prevent the plugin from enabling.
 */
internal object WorkspaceManager {
    private var workspaceRoot: Path? = null
    private var reconciler: WorkspaceReconciler? = null
    private var classpathEntries: List<Path> = emptyList()
    private var activePluginCount: Int = 0
    private var ideRefreshPending = false
    private var latestStatus = unavailableStatus()

    @Synchronized
    fun initialize(
        workspaceRoot: Path,
        classpathEntries: Iterable<Path> = emptyList(),
        activePluginCount: Int = 0
    ): WorkspaceUpdateResult {
        val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
        if (this.workspaceRoot != normalizedRoot) {
            this.workspaceRoot = normalizedRoot
            reconciler = null
            ideRefreshPending = false
        }
        this.classpathEntries = normalizeClasspath(classpathEntries)
        this.activePluginCount = activePluginCount.coerceAtLeast(0)
        return reconcile()
    }

    @Synchronized
    fun update(
        classpathEntries: Iterable<Path>,
        activePluginCount: Int
    ): WorkspaceUpdateResult {
        this.classpathEntries = normalizeClasspath(classpathEntries)
        this.activePluginCount = activePluginCount.coerceAtLeast(0)
        return reconcile()
    }

    @Synchronized
    fun refresh(): WorkspaceUpdateResult = reconcile()

    @Synchronized
    fun status(): WorkspaceStatus {
        val current = reconciler ?: return latestStatus
        latestStatus = runCatching {
            current.inspect(
                classpathEntries = classpathEntries,
                activePluginCount = activePluginCount
            ).copy(ideRefreshRecommended = ideRefreshPending)
        }.getOrElse { exception ->
            latestStatus.copy(
                state = WorkspaceState.ERROR,
                lastError = exception.toWorkspaceError()
            )
        }
        return latestStatus
    }

    private fun reconcile(): WorkspaceUpdateResult {
        val root = workspaceRoot
        if (root == null) {
            latestStatus = unavailableStatus()
            return WorkspaceUpdateResult(
                status = latestStatus,
                errors = listOf(requireNotNull(latestStatus.lastError))
            )
        }

        val current = reconciler ?: runCatching {
            WorkspaceReconciler(root, DefaultWorkspaceTemplates.load())
        }.getOrElse { exception ->
            latestStatus = WorkspaceStatus(
                workspaceRoot = root,
                schemaVersion = DefaultWorkspaceTemplates.SCHEMA_VERSION,
                templateVersion = DefaultWorkspaceTemplates.TEMPLATE_VERSION,
                activePluginCount = activePluginCount,
                classpathEntryCount = classpathEntries.size,
                conflictCount = 0,
                state = WorkspaceState.ERROR,
                lastError = exception.toWorkspaceError()
            )
            return WorkspaceUpdateResult(
                status = latestStatus,
                errors = listOf(requireNotNull(latestStatus.lastError))
            )
        }.also { reconciler = it }

        return runCatching {
            current.reconcile(classpathEntries, activePluginCount)
        }.getOrElse { exception ->
            WorkspaceUpdateResult(
                status = WorkspaceStatus(
                    workspaceRoot = root,
                    schemaVersion = DefaultWorkspaceTemplates.SCHEMA_VERSION,
                    templateVersion = DefaultWorkspaceTemplates.TEMPLATE_VERSION,
                    activePluginCount = activePluginCount,
                    classpathEntryCount = classpathEntries.size,
                    conflictCount = latestStatus.conflictCount,
                    state = WorkspaceState.ERROR,
                    lastError = exception.toWorkspaceError()
                ),
                errors = listOf(exception.toWorkspaceError())
            )
        }.let { result ->
            ideRefreshPending = ideRefreshPending || result.ideRefreshRecommended
            latestStatus = result.status.copy(
                ideRefreshRecommended = ideRefreshPending
            )
            result.copy(status = latestStatus)
        }
    }

    private fun unavailableStatus() = WorkspaceStatus(
        workspaceRoot = Path.of("plugins", "EternalScript").toAbsolutePath().normalize(),
        schemaVersion = DefaultWorkspaceTemplates.SCHEMA_VERSION,
        templateVersion = DefaultWorkspaceTemplates.TEMPLATE_VERSION,
        activePluginCount = 0,
        classpathEntryCount = 0,
        conflictCount = 0,
        state = WorkspaceState.ERROR,
        lastError = "Workspace manager is not initialized."
    )
}
