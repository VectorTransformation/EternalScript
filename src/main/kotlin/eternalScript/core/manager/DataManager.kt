package eternalScript.core.manager

import eternalScript.core.environment.EnvironmentRefreshRequest
import eternalScript.core.environment.ScriptEnvironmentCoordinator
import eternalScript.core.command.ProjectCommandController
import eternalScript.core.feedback.UserFeedback
import eternalScript.core.feedback.UserFeedbackChannels
import eternalScript.core.feedback.UserFeedbackEvent
import eternalScript.core.operation.ScriptOperation
import eternalScript.core.operation.ScriptOperationKind
import eternalScript.core.operation.ScriptOperationSnapshot
import eternalScript.core.operation.ScriptOperationTracker
import eternalScript.core.script.classloading.ScriptGenerationRegistry
import eternalScript.core.script.definition.ScriptCompilationCache
import eternalScript.core.script.generation.ScriptProjectLoadOutcome
import eternalScript.core.script.generation.ScriptProjectCheckOutcome
import eternalScript.core.script.generation.ScriptProjectCheckResult
import eternalScript.core.script.generation.ScriptProjectReport
import eternalScript.core.script.generation.ScriptProjectUnloadOutcome
import eternalScript.core.script.project.runtimeScriptProjectRepository
import eternalScript.core.runtime.GlobalExecution
import eternalScript.core.runtime.GlobalTaskOwner
import eternalScript.core.runtime.PluginHost
import eternalScript.core.runtime.ServerAccess
import eternalScript.core.workspace.WorkspaceManager
import eternalScript.core.workspace.WorkspaceUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level

internal class DataManager(
    private val host: PluginHost,
    private val server: ServerAccess,
    private val globalExecution: GlobalExecution,
    private val scriptManager: ScriptManager,
    private val environment: ScriptEnvironmentCoordinator,
    private val workspaceManager: WorkspaceManager,
    private val reloadManager: ReloadManager,
    private val compilationCache: ScriptCompilationCache,
    private val generationRegistry: ScriptGenerationRegistry,
    private val feedbackChannels: UserFeedbackChannels
) : PluginStartable, PluginStoppable, Listener, ProjectCommandController {
    private val operationLock = Any()
    private var operation: ScriptProjectOperation? = null
    private var lastUserOperation: ScriptOperationSnapshot? = null
    private var automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
    private var startupWorkspace: WorkspaceUpdateResult? = null
    private var pendingEnvironmentRefresh: EnvironmentRefreshRequest? = null
    private val initialLoad = InitialScriptLoadCoordinator()
    private val lifecycle = DataManagerLifecycle()
    private val scriptRepository = runtimeScriptProjectRepository(host.paths)

    override fun start() {
        synchronized(operationLock) {
            lifecycle.open()
            automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
            startupWorkspace = null
        }
        scriptManager.open()
        initializeWorkspace()
        registerServerLifecycle()
    }

    fun shutdown() {
        server.unregister(this)
        val current = synchronized(operationLock) {
            lifecycle.close()
            scriptManager.close()
            pendingEnvironmentRefresh = null
            initialLoad.reset()
            automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
            startupWorkspace = null
            operation.also { activeOperation ->
                operation = null
                activeOperation?.owner?.let(globalExecution::beginTaskOwnerShutdown)
            }
        }
        globalExecution.closeAdmission(current?.owner)
        current?.job?.cancel()
        val operationStopped = try {
            awaitOperationShutdown(
                operation = current?.job,
                timeoutMillis = OPERATION_SHUTDOWN_TIMEOUT_MILLIS,
                isGlobalThread = server.isGlobalTickThread,
                pumpGlobalTasks = {
                    current?.owner?.let(globalExecution::drainPendingGlobalTasks)
                }
            )
        } finally {
            current?.owner?.let(globalExecution::closeTaskOwner)
        }
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

    private fun initializeWorkspace() {
        reloadManager.start()
        synchronized(operationLock) {
            startupWorkspace = environment.initialize()
        }
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

    override fun unload(feedback: UserFeedback): Boolean {
        return startOperation(feedback, ScriptOperationKind.UNLOAD) { session ->
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
    }

    override fun reloadConfig(feedback: UserFeedback): Boolean {
        return startOperation(feedback, ScriptOperationKind.CONFIG_RELOAD) { session ->
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
    }

    override fun refreshWorkspace(feedback: UserFeedback): Boolean {
        return startOperation(feedback, ScriptOperationKind.WORKSPACE_UPDATE) { session ->
            emit(feedback, UserFeedbackEvent.WorkspaceUpdateStarted)
            if (!lifecycle.accepts(session)) return@startOperation false

            val capture = globalExecution.global { environment.capturePluginClasspath() }
            val (_, update) = environment.refreshClasspathAndWorkspace(capture)
            if (!lifecycle.accepts(session)) return@startOperation false

            emit(feedback, UserFeedbackEvent.WorkspaceUpdateFinished(update))
            update.successful
        }
    }

    override fun clearCache(feedback: UserFeedback): Boolean {
        return startOperation(feedback, ScriptOperationKind.CACHE_CLEAR) { session ->
            emit(feedback, UserFeedbackEvent.CacheClearStarted)
            val cleared = synchronized(operationLock) {
                if (!lifecycle.accepts(session)) {
                    false
                } else {
                    compilationCache.reset()
                    true
                }
            }
            if (!cleared) return@startOperation false
            emit(feedback, UserFeedbackEvent.CacheClearFinished)
            true
        }
    }

    private fun startOperation(
        feedback: UserFeedback,
        kind: ScriptOperationKind,
        announceBusy: Boolean = true,
        block: suspend (session: Long) -> Boolean
    ): Boolean {
        val current = synchronized(operationLock) {
            val session = lifecycle.openSession() ?: return false
            if (operation != null) return@synchronized null

            val tracker = ScriptOperationTracker(
                ScriptOperation(kind)
            )
            val owner = globalExecution.newTaskOwner()
            val created = globalExecution.launch(context = owner, start = CoroutineStart.LAZY) {
                tracker.start()
                try {
                    val result = if (lifecycle.accepts(session)) {
                        block(session)
                    } else {
                        null
                    }
                    if (result == null) {
                        tracker.cancel()
                    } else if (lifecycle.accepts(session)) {
                        tracker.complete(result)
                    } else {
                        tracker.cancel()
                    }
                } catch (_: CancellationException) {
                    tracker.cancel()
                } catch (exception: Throwable) {
                    tracker.fail()
                    val incidentId = UUID.randomUUID().toString().substring(0, 8)
                    host.logger.log(
                        Level.SEVERE,
                        "EternalScript project operation failed " +
                            "(incident=$incidentId, operation=${kind.name}).",
                        exception
                    )
                    if (kind.userVisible && lifecycle.accepts(session)) {
                        emit(
                            feedback,
                            UserFeedbackEvent.OperationFailed(kind, incidentId)
                        )
                    }
                }
            }
            val createdOperation = ScriptProjectOperation(tracker, created, owner, session)
            operation = createdOperation
            created.invokeOnCompletion {
                val snapshot = createdOperation.tracker.snapshot()
                synchronized(operationLock) {
                    if (operation === createdOperation) {
                        operation = null
                    }
                    if (snapshot.operation.kind.userVisible) {
                        lastUserOperation = snapshot
                    }
                }
                globalExecution.closeTaskOwner(owner)
                drainEnvironmentRefresh()
            }
            createdOperation
        }

        if (current == null) {
            if (announceBusy) {
                feedback.emit(UserFeedbackEvent.OperationBusy)
            }
            return false
        }

        current.job.start()
        return true
    }

    private fun registerServerLifecycle() {
        server.registerEvent(ServerLoadEvent::class, this, EventPriority.MONITOR) { event ->
            val shouldLoad = synchronized(operationLock) {
                initialLoad.onServerLoad(
                    reload = event.type == ServerLoadEvent.LoadType.RELOAD
                )
            }
            requestEnvironmentRefresh(
                EnvironmentRefreshRequest(
                    capture = environment.capturePluginClasspath(),
                    loadScripts = shouldLoad
                )
            )
        }
        server.registerEvent(PluginEnableEvent::class, this, EventPriority.MONITOR) { event ->
            if (event.plugin !== host.plugin) {
                requestEnvironmentRefresh(
                    EnvironmentRefreshRequest(environment.capturePluginClasspath())
                )
            }
        }
        server.registerEvent(PluginDisableEvent::class, this, EventPriority.LOWEST) { event ->
            if (event.plugin !== host.plugin) {
                val pluginName = event.plugin.name
                scriptManager.invalidateEnvironment()
                generationRegistry.invalidate(pluginName)
                val frozen = scriptManager.freezeForDisabledPlugin(pluginName)
                requestEnvironmentRefresh(
                    EnvironmentRefreshRequest(
                        capture = environment.capturePluginClasspath(excludedPlugin = event.plugin),
                        disabledPlugins = if (frozen) setOf(pluginName) else emptySet()
                    )
                )
            }
        }
        server.runGlobalDelayed(1L) {
            val shouldLoad = synchronized(operationLock) {
                initialLoad.onFallback(
                    sessionOpen = lifecycle.openSession() != null
                )
            }
            if (shouldLoad) {
                requestEnvironmentRefresh(
                    EnvironmentRefreshRequest(
                        capture = environment.capturePluginClasspath(),
                        loadScripts = true
                    )
                )
            }
        }
    }

    private fun requestEnvironmentRefresh(request: EnvironmentRefreshRequest) {
        val ready = synchronized(operationLock) {
            if (lifecycle.openSession() == null) return
            pendingEnvironmentRefresh =
                pendingEnvironmentRefresh?.merge(request) ?: request
            initialLoad.serverLoaded
        }
        if (ready) {
            drainEnvironmentRefresh()
        }
    }

    private fun drainEnvironmentRefresh() {
        while (true) {
            val request = synchronized(operationLock) {
                if (lifecycle.openSession() == null || operation != null) {
                    return
                }
                pendingEnvironmentRefresh.also {
                    pendingEnvironmentRefresh = null
                }
            } ?: return

            val started = startOperation(
                feedback = feedbackChannels.serverLog,
                kind = ScriptOperationKind.ENVIRONMENT_REFRESH,
                announceBusy = false
            ) { session ->
                processEnvironmentRefresh(request, session)
            }
            if (started) return

            synchronized(operationLock) {
                if (lifecycle.openSession() == null) return
                pendingEnvironmentRefresh =
                    request.merge(pendingEnvironmentRefresh)
            }
        }
    }

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
            val unloaded = scriptManager.unloadForDisabledPlugins(
                request.disabledPlugins
            )
            if (unloaded == null) {
                host.logger.warning(
                    "The script generation could not be unloaded after a plugin " +
                        "dependency was disabled. New script entries remain blocked; " +
                        "cleanup will retry."
                )
                delay(PLUGIN_DISABLE_RETRY_DELAY_MILLIS)
                if (lifecycle.accepts(session)) {
                    requestEnvironmentRefresh(request)
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
        synchronized(operationLock) {
            if (lifecycle.accepts(session)) {
                automaticLoadState = state
            }
        }
    }

    private fun takeStartupWorkspace(): WorkspaceUpdateResult =
        synchronized(operationLock) {
            startupWorkspace.also { startupWorkspace = null }
        } ?: WorkspaceUpdateResult(status = workspaceManager.status())

    private fun mergeStartupWorkspace(update: WorkspaceUpdateResult) {
        synchronized(operationLock) {
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

    override fun projectStatus(): ScriptProjectStatus {
        val operationState = synchronized(operationLock) {
            Triple(
                operation?.tracker?.snapshot(),
                lastUserOperation,
                automaticLoadState
            )
        }
        val (currentOperation, previousUserOperation, lastAutomaticLoad) = operationState
        return ScriptProjectStatus(
            generation = scriptManager.generationSnapshot(),
            availableSources = scriptRepository.paths().toSet(),
            currentUserOperation = currentOperation?.takeIf { snapshot ->
                snapshot.operation.kind.userVisible
            },
            lastUserOperation = previousUserOperation,
            backgroundMaintenance = currentOperation?.operation?.kind?.userVisible == false,
            automaticLoadState = lastAutomaticLoad
        )
    }

    private fun environmentReady(): Boolean =
        synchronized(operationLock) { initialLoad.serverLoaded } &&
            environment.isReady()

    private fun requireEnvironment(feedback: UserFeedback): Boolean {
        if (environmentReady()) return true
        feedback.emit(UserFeedbackEvent.EnvironmentPreparing)
        return false
    }

    private suspend fun emit(
        feedback: UserFeedback,
        event: UserFeedbackEvent
    ) {
        globalExecution.global {
            feedback.emit(event)
        }
    }
}

private data class ScriptProjectOperation(
    val tracker: ScriptOperationTracker,
    val job: Job,
    val owner: GlobalTaskOwner,
    val session: Long
)

private const val OPERATION_SHUTDOWN_TIMEOUT_MILLIS = 10_000L
private const val PLUGIN_DISABLE_RETRY_DELAY_MILLIS = 250L
