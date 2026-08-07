package eternalScript.core.manager

import eternalScript.core.environment.EnvironmentRefreshRequest
import eternalScript.core.environment.ScriptEnvironmentCoordinator
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
import eternalScript.core.the.GlobalTaskOwner
import eternalScript.core.the.Root
import eternalScript.core.workspace.WorkspaceManager
import eternalScript.core.workspace.WorkspaceUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.bukkit.Bukkit
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level

object DataManager : PluginStartable, PluginStoppable, Listener {
    private val operationLock = Any()
    private var operation: ScriptProjectOperation? = null
    private var lastUserOperation: ScriptOperationSnapshot? = null
    private var automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
    private var startupWorkspace: WorkspaceUpdateResult? = null
    private var pendingEnvironmentRefresh: EnvironmentRefreshRequest? = null
    private val initialLoad = InitialScriptLoadCoordinator()
    private val lifecycle = DataManagerLifecycle()
    private val scriptRepository by lazy(::runtimeScriptProjectRepository)

    override fun start() {
        synchronized(operationLock) {
            lifecycle.open()
            automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
            startupWorkspace = null
        }
        ScriptManager.open()
        initializeWorkspace()
        registerServerLifecycle()
    }

    fun shutdown() {
        Root.unregister(this as Listener)
        val current = synchronized(operationLock) {
            lifecycle.close()
            ScriptManager.close()
            pendingEnvironmentRefresh = null
            initialLoad.reset()
            automaticLoadState = AutomaticProjectLoadState.NOT_ATTEMPTED
            startupWorkspace = null
            operation.also { activeOperation ->
                operation = null
                activeOperation?.owner?.let(Root::beginGlobalTaskOwnerShutdown)
            }
        }
        Root.shutdown(current?.owner)
        current?.job?.cancel()
        val operationStopped = try {
            awaitOperationShutdown(
                operation = current?.job,
                timeoutMillis = OPERATION_SHUTDOWN_TIMEOUT_MILLIS,
                isGlobalThread = Bukkit.isGlobalTickThread(),
                pumpGlobalTasks = {
                    current?.owner?.let(Root::drainPendingGlobalTasks)
                }
            )
        } finally {
            current?.owner?.let(Root::closeGlobalTaskOwner)
        }
        if (!operationStopped) {
            Root.INSTANCE.logger.warning(
                "The active script project operation did not stop within " +
                    "${OPERATION_SHUTDOWN_TIMEOUT_MILLIS}ms; shutdown will continue " +
                    "with generation commits fenced."
            )
        }
        ScriptEnvironmentCoordinator.clear()
    }

    override fun stop() {
        shutdown()
    }

    private fun initializeWorkspace() {
        Root.start(ReloadManager)
        synchronized(operationLock) {
            startupWorkspace = ScriptEnvironmentCoordinator.initialize()
        }
    }

    internal fun reload(feedback: UserFeedback): Boolean {
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
                        activeProject = ScriptManager.generationSnapshot().exists
                    )
                )
                return@startOperation false
            }

            if (!lifecycle.accepts(session)) return@startOperation false
            val result = ScriptManager.load(project)
            emit(feedback, UserFeedbackEvent.ProjectReloadFinished(result))
            result.activated
        }
    }

    internal fun check(feedback: UserFeedback): Boolean {
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
            val result = ScriptManager.check(project)
            emit(feedback, UserFeedbackEvent.ProjectCheckFinished(total, result))
            result.passed
        }
    }

    internal fun unload(feedback: UserFeedback): Boolean {
        return startOperation(feedback, ScriptOperationKind.UNLOAD) { session ->
            if (!lifecycle.accepts(session)) return@startOperation false
            val before = ScriptManager.generationSnapshot()
            if (before.exists) {
                emit(
                    feedback,
                    UserFeedbackEvent.ProjectUnloadStarted(
                        sourceCount = before.sourceNames.size,
                        entryCount = before.entryNames.size
                    )
                )
            }
            val result = ScriptManager.clearNow()
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

    internal fun reloadConfig(feedback: UserFeedback): Boolean {
        return startOperation(feedback, ScriptOperationKind.CONFIG_RELOAD) { session ->
            emit(feedback, UserFeedbackEvent.ConfigurationReloadStarted)
            val capture = Root.global {
                if (lifecycle.accepts(session)) {
                    ReloadManager.reload()
                }
                ScriptEnvironmentCoordinator.capturePluginClasspath()
            }
            if (!lifecycle.accepts(session)) return@startOperation false
            val (_, workspace) = ScriptEnvironmentCoordinator.refreshClasspathAndWorkspace(capture)
            emit(feedback, UserFeedbackEvent.ConfigurationReloadFinished(workspace))
            workspace.successful
        }
    }

    internal fun refreshWorkspace(feedback: UserFeedback): Boolean {
        return startOperation(feedback, ScriptOperationKind.WORKSPACE_UPDATE) { session ->
            emit(feedback, UserFeedbackEvent.WorkspaceUpdateStarted)
            if (!lifecycle.accepts(session)) return@startOperation false

            val capture = Root.global { ScriptEnvironmentCoordinator.capturePluginClasspath() }
            val (_, update) = ScriptEnvironmentCoordinator.refreshClasspathAndWorkspace(capture)
            if (!lifecycle.accepts(session)) return@startOperation false

            emit(feedback, UserFeedbackEvent.WorkspaceUpdateFinished(update))
            update.successful
        }
    }

    internal fun clearCache(feedback: UserFeedback): Boolean {
        return startOperation(feedback, ScriptOperationKind.CACHE_CLEAR) { session ->
            emit(feedback, UserFeedbackEvent.CacheClearStarted)
            val cleared = synchronized(operationLock) {
                if (!lifecycle.accepts(session)) {
                    false
                } else {
                    ScriptCompilationCache.reset()
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
            val owner = Root.newGlobalTaskOwner()
            val created = Root.launch(context = owner, start = CoroutineStart.LAZY) {
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
                    Root.INSTANCE.logger.log(
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
                Root.closeGlobalTaskOwner(owner)
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
        Root.register(ServerLoadEvent::class, this, EventPriority.MONITOR) { event ->
            val shouldLoad = synchronized(operationLock) {
                initialLoad.onServerLoad(
                    reload = event.type == ServerLoadEvent.LoadType.RELOAD
                )
            }
            requestEnvironmentRefresh(
                EnvironmentRefreshRequest(
                    capture = ScriptEnvironmentCoordinator.capturePluginClasspath(),
                    loadScripts = shouldLoad
                )
            )
        }
        Root.register(PluginEnableEvent::class, this, EventPriority.MONITOR) { event ->
            if (event.plugin !== Root.INSTANCE) {
                requestEnvironmentRefresh(
                    EnvironmentRefreshRequest(ScriptEnvironmentCoordinator.capturePluginClasspath())
                )
            }
        }
        Root.register(PluginDisableEvent::class, this, EventPriority.LOWEST) { event ->
            if (event.plugin !== Root.INSTANCE) {
                val pluginName = event.plugin.name
                ScriptManager.invalidateEnvironment()
                ScriptGenerationRegistry.invalidate(pluginName)
                val frozen = ScriptManager.freezeForDisabledPlugin(pluginName)
                requestEnvironmentRefresh(
                    EnvironmentRefreshRequest(
                        capture = ScriptEnvironmentCoordinator.capturePluginClasspath(excludedPlugin = event.plugin),
                        disabledPlugins = if (frozen) setOf(pluginName) else emptySet()
                    )
                )
            }
        }
        Root.INSTANCE.server.globalRegionScheduler.runDelayed(
            Root.INSTANCE,
            { _ ->
                val shouldLoad = synchronized(operationLock) {
                    initialLoad.onFallback(
                        sessionOpen = lifecycle.openSession() != null
                    )
                }
                if (shouldLoad) {
                    requestEnvironmentRefresh(
                        EnvironmentRefreshRequest(
                            capture = ScriptEnvironmentCoordinator.capturePluginClasspath(),
                            loadScripts = true
                        )
                    )
                }
            },
            1L
        )
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
                feedback = UserFeedbackChannels.serverLog,
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
            val (_, workspace) = ScriptEnvironmentCoordinator.refreshClasspathAndWorkspace(initialRequest.capture)
            if (initialRequest.loadScripts) {
                mergeStartupWorkspace(workspace)
            } else {
                emit(
                    UserFeedbackChannels.serverLog,
                    UserFeedbackEvent.WorkspaceMaintenance(workspace)
                )
            }
            initialRequest.copy(metadataApplied = true)
        }
        if (!lifecycle.accepts(session)) return false

        if (request.disabledPlugins.isNotEmpty()) {
            val unloaded = ScriptManager.unloadForDisabledPlugins(
                request.disabledPlugins
            )
            if (unloaded == null) {
                Root.INSTANCE.logger.warning(
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
                UserFeedbackChannels.serverLog,
                UserFeedbackEvent.StartupSummary(
                    workspace = takeStartupWorkspace(),
                    sourceCount = 0,
                    loadResult = null
                )
            )
            return true
        }
        if (!lifecycle.accepts(session)) return false

        val before = ScriptManager.generationSnapshot()
        recordAutomaticLoad(
            session,
            if (before.exists) {
                AutomaticProjectLoadState.FAILED_PRESERVED
            } else {
                AutomaticProjectLoadState.FAILED_INACTIVE
            }
        )
        val result = ScriptManager.load(project)
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
            UserFeedbackChannels.serverLog,
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
        } ?: WorkspaceUpdateResult(status = WorkspaceManager.status())

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

    internal fun projectStatus(): ScriptProjectStatus {
        val operationState = synchronized(operationLock) {
            Triple(
                operation?.tracker?.snapshot(),
                lastUserOperation,
                automaticLoadState
            )
        }
        val (currentOperation, previousUserOperation, lastAutomaticLoad) = operationState
        return ScriptProjectStatus(
            generation = ScriptManager.generationSnapshot(),
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
            ScriptEnvironmentCoordinator.isReady()

    private fun requireEnvironment(feedback: UserFeedback): Boolean {
        if (environmentReady()) return true
        feedback.emit(UserFeedbackEvent.EnvironmentPreparing)
        return false
    }

    private suspend fun emit(
        feedback: UserFeedback,
        event: UserFeedbackEvent
    ) {
        Root.global {
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
