package eternalScript.core.manager

import eternalScript.core.command.ProjectCommandController
import eternalScript.core.environment.EnvironmentRefreshRequest
import eternalScript.core.environment.ScriptEnvironmentCoordinator
import eternalScript.core.feedback.UserFeedback
import eternalScript.core.feedback.UserFeedbackChannels
import eternalScript.core.feedback.UserFeedbackEvent
import eternalScript.core.operation.ScriptOperationKind
import eternalScript.core.runtime.GlobalExecution
import eternalScript.core.runtime.PluginHost
import eternalScript.core.runtime.ServerAccess
import eternalScript.core.script.classloading.ScriptGenerationRegistry
import eternalScript.core.script.definition.ScriptCompilationCache
import eternalScript.core.script.generation.ScriptProjectCheckOutcome
import eternalScript.core.script.generation.ScriptProjectCheckResult
import eternalScript.core.script.generation.ScriptProjectLoadOutcome
import eternalScript.core.script.generation.ScriptProjectReport
import eternalScript.core.script.generation.ScriptProjectUnloadOutcome
import eternalScript.core.script.project.runtimeScriptProjectRepository
import eternalScript.core.workspace.WorkspaceManager
import eternalScript.core.workspace.WorkspaceUpdateResult
import kotlinx.coroutines.delay

/** Coordinates project commands while delegating operation and environment state. */
internal class ProjectController(
    private val host: PluginHost,
    server: ServerAccess,
    private val globalExecution: GlobalExecution,
    private val scriptManager: ScriptManager,
    private val environment: ScriptEnvironmentCoordinator,
    private val workspaceManager: WorkspaceManager,
    private val reloadManager: ReloadManager,
    private val compilationCache: ScriptCompilationCache,
    generationRegistry: ScriptGenerationRegistry,
    private val feedbackChannels: UserFeedbackChannels
) : PluginStartable, PluginStoppable, ProjectCommandController {
    private val lifecycle = ProjectLifecycleFence()
    private val stateLock = Any()
    private var automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
    private var startupWorkspace: WorkspaceUpdateResult? = null
    private val scriptRepository = runtimeScriptProjectRepository(host.paths)

    private val operationController: ScriptOperationController
    private val environmentRefreshController: EnvironmentRefreshController
    private val serverLifecycleListener: ServerLifecycleListener

    init {
        operationController = ScriptOperationController(
            lifecycle = lifecycle,
            runtime = GlobalScriptOperationRuntime(globalExecution, server),
            logger = host.logger,
            emit = ::emit,
            onIdle = { environmentRefreshController.drain() }
        )
        environmentRefreshController = EnvironmentRefreshController(
            lifecycle = lifecycle,
            canDrain = { serverLifecycleListener.serverLoaded },
            operationActive = operationController::isActive,
            dispatch = { request ->
                operationController.start(
                    feedback = feedbackChannels.serverLog,
                    kind = ScriptOperationKind.ENVIRONMENT_REFRESH,
                    announceBusy = false
                ) { session ->
                    processEnvironmentRefresh(request, session)
                }
            }
        )
        serverLifecycleListener = ServerLifecycleListener(
            host = host,
            server = server,
            lifecycle = lifecycle,
            scriptManager = scriptManager,
            environment = environment,
            generationRegistry = generationRegistry,
            requestRefresh = environmentRefreshController::request
        )
    }

    override fun start() {
        lifecycle.open()
        synchronized(stateLock) {
            automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
            startupWorkspace = null
        }
        operationController.reset()
        environmentRefreshController.open()
        scriptManager.open()
        reloadManager.start()
        val initialWorkspace = environment.initialize()
        synchronized(stateLock) {
            startupWorkspace = initialWorkspace
        }
        serverLifecycleListener.start()
    }

    fun shutdown() {
        serverLifecycleListener.stop()
        lifecycle.close()
        scriptManager.close()
        environmentRefreshController.close()
        synchronized(stateLock) {
            automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
            startupWorkspace = null
        }

        val operationStopped = operationController.shutdown()
        if (!operationStopped) {
            host.logger.warning(
                "The active script project operation did not stop within " +
                    "${OPERATION_SHUTDOWN_TIMEOUT_MILLIS}ms; shutdown will continue " +
                    "with generation commits fenced."
            )
        }
        environment.clear()
    }

    override fun stop() {
        shutdown()
    }

    override fun reload(feedback: UserFeedback): Boolean {
        if (!requireEnvironment(feedback)) return false
        return startOperation(feedback, ScriptOperationKind.RELOAD) { session ->
            val project = scriptRepository.snapshot()
            if (!lifecycle.accepts(session)) return@startOperation false
            val total = project?.files?.size ?: 0
            emit(feedback, UserFeedbackEvent.ProjectReloadStarted(total))
            if (project == null) {
                emit(
                    feedback,
                    UserFeedbackEvent.ProjectSourceMissing(
                        activeProject = scriptManager.generationSnapshot().exists
                    )
                )
                return@startOperation false
            }

            if (!lifecycle.accepts(session)) return@startOperation false
            val result = scriptManager.load(project)
            emit(feedback, UserFeedbackEvent.ProjectReloadFinished(result))
            result.activated
        }
    }

    override fun check(feedback: UserFeedback): Boolean {
        if (!requireEnvironment(feedback)) return false
        return startOperation(feedback, ScriptOperationKind.CHECK) { session ->
            val project = scriptRepository.snapshot()
            if (!lifecycle.accepts(session)) return@startOperation false
            val total = project?.files?.size ?: 0
            emit(feedback, UserFeedbackEvent.ProjectCheckStarted(total))
            if (project == null) {
                emit(
                    feedback,
                    UserFeedbackEvent.ProjectCheckFinished(
                        sourceCount = 0,
                        result = ScriptProjectCheckResult(
                            outcome = ScriptProjectCheckOutcome.NO_SOURCES,
                            report = ScriptProjectReport()
                        )
                    )
                )
                return@startOperation false
            }

            if (!lifecycle.accepts(session)) return@startOperation false
            val result = scriptManager.check(project)
            emit(feedback, UserFeedbackEvent.ProjectCheckFinished(total, result))
            result.passed
        }
    }

    override fun unload(feedback: UserFeedback): Boolean =
        startOperation(feedback, ScriptOperationKind.UNLOAD) { session ->
            if (!lifecycle.accepts(session)) return@startOperation false
            val before = scriptManager.generationSnapshot()
            if (before.exists) {
                emit(
                    feedback,
                    UserFeedbackEvent.ProjectUnloadStarted(
                        sourceCount = before.sourceNames.size,
                        entryCount = before.entryNames.size
                    )
                )
            }
            val result = scriptManager.clearNow()
            emit(
                feedback,
                UserFeedbackEvent.ProjectUnloadFinished(
                    result = result,
                    diskSourceCount = scriptRepository.paths().count()
                )
            )
            result.outcome != ScriptProjectUnloadOutcome.REJECTED
        }

    override fun reloadConfig(feedback: UserFeedback): Boolean =
        startOperation(feedback, ScriptOperationKind.CONFIG_RELOAD) { session ->
            emit(feedback, UserFeedbackEvent.ConfigurationReloadStarted)
            val capture = globalExecution.global {
                if (lifecycle.accepts(session)) {
                    reloadManager.reload()
                }
                environment.capturePluginClasspath()
            }
            if (!lifecycle.accepts(session)) return@startOperation false
            val (_, workspace) = environment.refreshClasspathAndWorkspace(capture)
            emit(feedback, UserFeedbackEvent.ConfigurationReloadFinished(workspace))
            workspace.successful
        }

    override fun refreshWorkspace(feedback: UserFeedback): Boolean =
        startOperation(feedback, ScriptOperationKind.WORKSPACE_UPDATE) { session ->
            emit(feedback, UserFeedbackEvent.WorkspaceUpdateStarted)
            if (!lifecycle.accepts(session)) return@startOperation false

            val capture = globalExecution.global { environment.capturePluginClasspath() }
            val (_, update) = environment.refreshClasspathAndWorkspace(capture)
            if (!lifecycle.accepts(session)) return@startOperation false

            emit(feedback, UserFeedbackEvent.WorkspaceUpdateFinished(update))
            update.successful
        }

    override fun clearCache(feedback: UserFeedback): Boolean =
        startOperation(feedback, ScriptOperationKind.CACHE_CLEAR) { session ->
            emit(feedback, UserFeedbackEvent.CacheClearStarted)
            if (!lifecycle.accepts(session)) return@startOperation false
            compilationCache.reset()
            if (!lifecycle.accepts(session)) return@startOperation false
            emit(feedback, UserFeedbackEvent.CacheClearFinished)
            true
        }

    override fun projectStatus(): ScriptProjectStatus {
        val operations = operationController.snapshot()
        val automaticLoad = synchronized(stateLock) { automaticLoadState }
        return ScriptProjectStatus(
            generation = scriptManager.generationSnapshot(),
            availableSources = scriptRepository.paths().toSet(),
            currentUserOperation = operations.current?.takeIf { snapshot ->
                snapshot.operation.kind.userVisible
            },
            lastUserOperation = operations.lastUser,
            backgroundMaintenance =
                operations.current?.operation?.kind?.userVisible == false,
            automaticLoadState = automaticLoad
        )
    }

    private fun startOperation(
        feedback: UserFeedback,
        kind: ScriptOperationKind,
        announceBusy: Boolean = true,
        block: suspend (session: Long) -> Boolean
    ): Boolean = operationController.start(feedback, kind, announceBusy, block)

    private suspend fun processEnvironmentRefresh(
        initialRequest: EnvironmentRefreshRequest,
        session: Long
    ): Boolean {
        val request = if (initialRequest.metadataApplied) {
            initialRequest
        } else {
            val (_, workspace) = environment.refreshClasspathAndWorkspace(initialRequest.capture)
            if (initialRequest.loadScripts) {
                mergeStartupWorkspace(workspace)
            } else {
                emit(
                    feedbackChannels.serverLog,
                    UserFeedbackEvent.WorkspaceMaintenance(workspace)
                )
            }
            initialRequest.copy(metadataApplied = true)
        }
        if (!lifecycle.accepts(session)) return false

        if (request.disabledPlugins.isNotEmpty()) {
            val unloaded = scriptManager.unloadForDisabledPlugins(request.disabledPlugins)
            if (unloaded == null) {
                host.logger.warning(
                    "The script generation could not be unloaded after a plugin " +
                        "dependency was disabled. New script entries remain blocked; " +
                        "cleanup will retry."
                )
                delay(PLUGIN_DISABLE_RETRY_DELAY_MILLIS)
                if (lifecycle.accepts(session)) {
                    environmentRefreshController.request(request)
                }
                return false
            }
        }
        if (!lifecycle.accepts(session)) return false
        if (!request.loadScripts || !lifecycle.accepts(session)) return true

        val project = scriptRepository.snapshot()
        if (project == null) {
            recordAutomaticLoad(session, AutomaticProjectLoadState.EMPTY)
            emit(
                feedbackChannels.serverLog,
                UserFeedbackEvent.StartupSummary(
                    workspace = takeStartupWorkspace(),
                    sourceCount = 0,
                    loadResult = null
                )
            )
            return true
        }
        if (!lifecycle.accepts(session)) return false

        val before = scriptManager.generationSnapshot()
        recordAutomaticLoad(
            session,
            if (before.exists) {
                AutomaticProjectLoadState.FAILED_PRESERVED
            } else {
                AutomaticProjectLoadState.FAILED_INACTIVE
            }
        )
        val result = scriptManager.load(project)
        val state = when (result.outcome) {
            ScriptProjectLoadOutcome.ACTIVATED -> AutomaticProjectLoadState.ACTIVATED
            ScriptProjectLoadOutcome.REJECTED_PREVIOUS_ACTIVE,
            ScriptProjectLoadOutcome.REJECTED_ACTIVE_CHANGED ->
                AutomaticProjectLoadState.FAILED_PRESERVED
            ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE ->
                AutomaticProjectLoadState.FAILED_INACTIVE
        }
        recordAutomaticLoad(session, state)
        emit(
            feedbackChannels.serverLog,
            UserFeedbackEvent.StartupSummary(
                workspace = takeStartupWorkspace(),
                sourceCount = project.files.size,
                loadResult = result
            )
        )
        return result.activated
    }

    private fun recordAutomaticLoad(
        session: Long,
        state: AutomaticProjectLoadState
    ) {
        if (!lifecycle.accepts(session)) return
        synchronized(stateLock) {
            if (lifecycle.accepts(session)) {
                automaticLoadState = state
            }
        }
    }

    private fun takeStartupWorkspace(): WorkspaceUpdateResult =
        synchronized(stateLock) {
            startupWorkspace.also { startupWorkspace = null }
        } ?: WorkspaceUpdateResult(status = workspaceManager.status())

    private fun mergeStartupWorkspace(update: WorkspaceUpdateResult) {
        synchronized(stateLock) {
            val initial = startupWorkspace
            startupWorkspace = if (initial == null) {
                update
            } else {
                WorkspaceUpdateResult(
                    status = update.status,
                    createdFiles = (initial.createdFiles + update.createdFiles).distinct(),
                    updatedFiles = (initial.updatedFiles + update.updatedFiles).distinct(),
                    conflictFiles = (initial.conflictFiles + update.conflictFiles).distinct(),
                    errors = (initial.errors + update.errors).distinct(),
                    ideRefreshRecommended = initial.ideRefreshRecommended ||
                        update.ideRefreshRecommended
                )
            }
        }
    }

    private fun environmentReady(): Boolean =
        serverLifecycleListener.serverLoaded && environment.isReady()

    private fun requireEnvironment(feedback: UserFeedback): Boolean {
        if (environmentReady()) return true
        feedback.emit(UserFeedbackEvent.EnvironmentPreparing)
        return false
    }

    private suspend fun emit(feedback: UserFeedback, event: UserFeedbackEvent) {
        globalExecution.global { feedback.emit(event) }
    }
}

private const val OPERATION_SHUTDOWN_TIMEOUT_MILLIS = 10_000L
private const val PLUGIN_DISABLE_RETRY_DELAY_MILLIS = 250L
