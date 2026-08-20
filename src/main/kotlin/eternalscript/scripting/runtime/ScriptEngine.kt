package eternalscript.scripting.runtime

import eternalscript.api.ScriptDiagnostic
import eternalscript.api.ScriptDiagnosticPhase
import eternalscript.api.ScriptEngineState
import eternalscript.api.ScriptInfo
import eternalscript.api.ScriptOperation
import eternalscript.api.ScriptOperationResult
import eternalscript.api.ScriptOperationStatus
import eternalscript.api.ScriptSnapshot
import eternalscript.config.PluginPaths
import eternalscript.feedback.FeedbackKey
import eternalscript.feedback.FeedbackLevel
import eternalscript.feedback.SystemFeedback
import eternalscript.feedback.feedbackText
import eternalscript.feedback.systemFeedback
import eternalscript.ide.EternalScriptIdeEnvironmentPublisher
import eternalscript.scripting.compilation.ScriptCandidateSelection
import eternalscript.scripting.compilation.ScriptCompilationCoordinator
import eternalscript.scripting.compilation.ScriptCompilationEnvironmentFactory
import eternalscript.scripting.compilation.ScriptCompilationOutcome
import eternalscript.scripting.compilation.ScriptEnvironmentSnapshot
import eternalscript.scripting.dependency.planScriptUnload
import eternalscript.scripting.repl.SharedReplDiagnostic
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.source.ScriptPathTransition
import eternalscript.scripting.source.ScriptSourceFile
import eternalscript.scripting.source.ScriptSourceRepository
import eternalscript.scripting.source.ScriptTargetPreparation
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.List.copyOf
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class ScriptEngine(
    private val plugin: JavaPlugin,
    private val paths: PluginPaths,
    private val sources: ScriptSourceRepository,
    private val system: (SystemFeedback) -> Unit,
    private val ideEnvironment: EternalScriptIdeEnvironmentPublisher
) {
    private data class PendingOperation(
        val token: Long,
        val operation: ScriptOperation,
        val affectedPaths: List<String>,
        val future: CompletableFuture<ScriptOperationResult>,
        var requestId: Long? = null,
        var sourceTransition: ScriptPathTransition? = null
    )

    private val generation = ScriptGenerationState()
    private val commandRegistry = ScriptCommandRegistry(plugin, system)
    private val applier = ScriptGenerationApplier(plugin, generation, commandRegistry, system)
    private val operationDispatcher = ScriptOperationDispatcher(plugin)
    private val operationReporter = ScriptOperationReporter({ generation.revision }, system)
    private val operationSequence = AtomicLong()
    private val sourceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EternalScript-Sources").apply {
            isDaemon = true
            contextClassLoader = ClassLoader.getSystemClassLoader()
        }
    }
    private val coordinator = ScriptCompilationCoordinator(
        plugin,
        paths.scriptCacheV5Directory
    )
    private val publishedSnapshot = AtomicReference(
        ScriptSnapshot(0, ScriptEngineState.STARTING, null, emptyList())
    )
    private val pendingLock = Any()
    private var engineState = ScriptEngineState.STARTING
    @Volatile
    private var pending: PendingOperation? = null
    @Volatile
    private var shuttingDown = false

    fun snapshot(): ScriptSnapshot = publishedSnapshot.get()

    fun startup(): ScriptOperationResult {
        assertMainThread()
        check(engineState == ScriptEngineState.STARTING) { "Script engine has already started" }
        val candidate = readAll(ScriptOperation.RELOAD).getOrElse { error ->
            engineState = ScriptEngineState.READY
            publishSnapshot()
            return operationFailure(error, ScriptOperation.RELOAD)
        }
        val environment = captureEnvironment(ScriptOperation.RELOAD).getOrElse { error ->
            engineState = ScriptEngineState.READY
            publishSnapshot()
            return operationFailure(error, ScriptOperation.RELOAD)
        }
        if (candidate.isEmpty()) {
            coordinator.environmentBlocking(environment).fold(
                onSuccess = ideEnvironment::publishEnvironmentIfChanged,
                onFailure = { Unit }
            )
            engineState = ScriptEngineState.READY
            publishSnapshot()
            return result(ScriptOperation.RELOAD, ScriptOperationStatus.SUCCESS)
        }
        val request = coordinator.request(
            activeRevision = generation.revision,
            activeSources = emptyList(),
            candidateSources = candidate.map(LoadedScript::source),
            environmentSnapshot = environment,
            allowStartupCache = true
        )
        val outcome = coordinator.compileBlocking(request)
        var applied = applyStartup(candidate, outcome)
        if (
            outcome is ScriptCompilationOutcome.Success &&
            outcome.cacheHit &&
            applied.cacheRetryable
        ) {
            val retry = coordinator.request(
                activeRevision = generation.revision,
                activeSources = emptyList(),
                candidateSources = candidate.map(LoadedScript::source),
                environmentSnapshot = environment,
                allowStartupCache = false,
                forceAll = true
            )
            applied = applyStartup(candidate, coordinator.compileBlocking(retry))
        }
        engineState = ScriptEngineState.READY
        publishSnapshot()
        return applied.result
    }

    fun reload(): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.RELOAD) { future ->
        if (rejectIfBusy(ScriptOperation.RELOAD, future)) return@onMain
        val operation = beginOperation(ScriptOperation.RELOAD, future)
        readAllAsync(operation) { candidate ->
            submit(operation, candidate)
        }
    }

    fun recompile(): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.RECOMPILE) { future ->
        if (rejectIfBusy(ScriptOperation.RECOMPILE, future)) return@onMain
        val candidate = generation.loaded.map(LoadedScript::withoutRuntime)
        val operation = beginOperation(ScriptOperation.RECOMPILE, future)
        submit(operation, candidate, forceAll = true)
    }

    fun load(path: String): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.LOAD) { future ->
        if (rejectIfBusy(ScriptOperation.LOAD, future)) return@onMain
        val operation = beginOperation(ScriptOperation.LOAD, future, listOf(path))
        resolveTargetAsync(operation, { sources.prepareLoad(path) }) { transition ->
            applyTargetAsync(
                operation,
                transition,
                work = { sources.all().map { source -> source.toLoadedScript() } }
            ) { available ->
                val replacements = available.filter { source -> transition.target.contains(source.name) }
                if (replacements.isEmpty()) {
                    val status = if (transition.changed) {
                        ScriptOperationStatus.SUCCESS
                    } else {
                        ScriptOperationStatus.NO_CHANGE
                    }
                    finishOperation(
                        operation,
                        result(ScriptOperation.LOAD, status, listOf(transition.target.path))
                    )
                    return@applyTargetAsync
                }

                val universe = available.associateByTo(linkedMapOf(), LoadedScript::name)
                generation.loaded.forEach { active -> universe[active.name] = active.withoutRuntime() }
                replacements.forEach { replacement -> universe[replacement.name] = replacement }
                val candidate = universe.values.sortedWith(sourceComparator)
                submit(
                    operation,
                    candidate,
                    selection = ScriptCandidateSelection.Load(
                        replacements.mapTo(linkedSetOf(), LoadedScript::name),
                        generation.loaded.mapTo(linkedSetOf(), LoadedScript::name)
                    )
                )
            }
        }
    }

    fun unload(path: String): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.UNLOAD) { future ->
        if (rejectIfBusy(ScriptOperation.UNLOAD, future)) return@onMain
        val operation = beginOperation(ScriptOperation.UNLOAD, future, listOf(path))
        resolveTargetAsync(operation, { sources.prepareUnload(path) }) { transition ->
            val unloadPlan = planScriptUnload(
                generation.compiled?.graph,
                generation.loaded.map(LoadedScript::name),
                transition.target
            )
            if (unloadPlan.missingGraphPaths.isNotEmpty()) {
                finishOperation(
                    operation,
                    result(
                        ScriptOperation.UNLOAD,
                        ScriptOperationStatus.FAILED,
                        unloadPlan.selectedPaths.sorted(),
                        listOf(
                            ScriptDiagnostic(
                                transition.target.path,
                                ScriptDiagnosticPhase.SOURCE,
                                "Cannot verify unload dependencies because the active dependency graph is missing: " +
                                    unloadPlan.missingGraphPaths.sorted().joinToString()
                            )
                        )
                    )
                )
                return@resolveTargetAsync
            }
            if (unloadPlan.blockingConsumers.isNotEmpty()) {
                finishOperation(
                    operation,
                    result(
                        ScriptOperation.UNLOAD,
                        ScriptOperationStatus.FAILED,
                        (unloadPlan.selectedPaths + unloadPlan.blockingConsumers).sorted(),
                        listOf(
                            ScriptDiagnostic(
                                transition.target.path,
                                ScriptDiagnosticPhase.SOURCE,
                                "Cannot unload while active script(s) outside the target depend on it: " +
                                    unloadPlan.blockingConsumers.sorted().joinToString() +
                                    ". Unload those consumers first or unload a directory that contains both."
                            )
                        )
                    )
                )
                return@resolveTargetAsync
            }
            val candidate = generation.loaded
                .filterNot { source -> source.name in unloadPlan.selectedPaths }
                .map(LoadedScript::withoutRuntime)
            applyTargetAsync(operation, transition, work = { Unit }) {
                if (unloadPlan.selectedPaths.isEmpty()) {
                    val status = if (transition.changed) {
                        ScriptOperationStatus.SUCCESS
                    } else {
                        ScriptOperationStatus.NO_CHANGE
                    }
                    finishOperation(
                        operation,
                        result(ScriptOperation.UNLOAD, status, listOf(transition.target.path))
                    )
                } else {
                    submit(operation, candidate)
                }
            }
        }
    }

    fun clear(): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.CLEAR) { future ->
        val operation = PendingOperation(
            operationSequence.incrementAndGet(),
            ScriptOperation.CLEAR,
            emptyList(),
            future
        )
        var clearAlreadyRunning = false
        val cancelled = synchronized(pendingLock) {
            val current = pending
            if (current?.operation == ScriptOperation.CLEAR) {
                clearAlreadyRunning = true
                null
            } else {
                pending = operation
                current
            }
        }
        if (clearAlreadyRunning) {
            future.complete(result(ScriptOperation.CLEAR, ScriptOperationStatus.BUSY))
            return@onMain
        }
        publishSnapshot()
        coordinator.invalidate()
        if (cancelled != null) {
            completeOperation(
                cancelled,
                result(cancelled.operation, ScriptOperationStatus.CANCELLED, cancelled.affectedPaths)
            )
        }
        val affected = if (generation.loaded.isEmpty()) emptyList() else applier.clear()
        val status = if (cancelled != null || affected.isNotEmpty()) {
            ScriptOperationStatus.SUCCESS
        } else {
            ScriptOperationStatus.NO_CHANGE
        }
        finishOperation(operation, result(ScriptOperation.CLEAR, status, affected))
    }

    fun shutdown() {
        assertMainThread()
        if (shuttingDown) return
        shuttingDown = true
        engineState = ScriptEngineState.DISABLED
        operationDispatcher.close { operation -> result(operation, ScriptOperationStatus.DISABLED) }
        cancelPending()
        sourceExecutor.shutdownNow()
        runCatching { sourceExecutor.awaitTermination(5, TimeUnit.SECONDS) }
        coordinator.invalidate(environment = true)
        applier.shutdown()
        coordinator.close()
        publishSnapshot()
    }

    private fun submit(
        operation: PendingOperation,
        candidate: List<LoadedScript>,
        forceAll: Boolean = false,
        selection: ScriptCandidateSelection = ScriptCandidateSelection.Exact
    ) {
        if (pending?.token != operation.token || shuttingDown) return
        val environment = captureEnvironment(operation.operation).getOrElse { error ->
            finishOperation(operation, operationFailure(error, operation.operation))
            return
        }
        val request = coordinator.request(
            activeRevision = generation.revision,
            activeSources = generation.loaded.map(LoadedScript::source),
            candidateSources = candidate.map(LoadedScript::source),
            selection = selection,
            environmentSnapshot = environment,
            forceAll = forceAll
        )
        operation.requestId = request.id
        publishSnapshot()
        coordinator.compileAsync(
            request,
            dispatchFailure = { error ->
                completeOperationWithoutMain(
                    operation,
                    result(
                        operation.operation,
                        ScriptOperationStatus.DISABLED,
                        operation.affectedPaths,
                        listOf(
                            ScriptDiagnostic(
                                "<compiler>",
                                ScriptDiagnosticPhase.COMPILE,
                                "Could not return script compilation to the main thread: " +
                                    (error.message ?: error.javaClass.name)
                            )
                        )
                    )
                )
            },
            callback = ::handleOutcome
        )
    }

    private fun beginOperation(
        operation: ScriptOperation,
        future: CompletableFuture<ScriptOperationResult>,
        requestedPaths: List<String> = emptyList()
    ): PendingOperation = PendingOperation(
        operationSequence.incrementAndGet(),
        operation,
        requestedPaths,
        future
    ).also { started ->
        synchronized(pendingLock) {
            check(pending == null) { "Another script operation is already pending" }
            pending = started
        }
        publishSnapshot()
    }

    private fun resolveTargetAsync(
        operation: PendingOperation,
        resolve: () -> ScriptTargetPreparation,
        success: (ScriptPathTransition) -> Unit
    ) {
        runSourceTask(operation, "script target resolution", resolve) { prepared ->
            prepared.fold(
                onSuccess = { target ->
                    when (target) {
                        is ScriptTargetPreparation.Ready -> {
                            operation.sourceTransition = target.transition
                            success(target.transition)
                        }
                        is ScriptTargetPreparation.Invalid -> finishOperation(
                            operation,
                            result(
                                operation.operation,
                                ScriptOperationStatus.INVALID_PATH,
                                operation.affectedPaths,
                                listOf(
                                    ScriptDiagnostic(
                                        operation.affectedPaths.firstOrNull() ?: "<target>",
                                        ScriptDiagnosticPhase.SOURCE,
                                        target.reason
                                    )
                                )
                            )
                        )
                        is ScriptTargetPreparation.NotFound -> finishOperation(
                            operation,
                            result(
                                operation.operation,
                                ScriptOperationStatus.NOT_FOUND,
                                listOf(target.path)
                            )
                        )
                    }
                },
                onFailure = { error ->
                    finishOperation(operation, operationFailure(error, operation.operation))
                }
            )
        }
    }

    private fun <T> applyTargetAsync(
        operation: PendingOperation,
        transition: ScriptPathTransition,
        work: () -> T,
        success: (T) -> Unit
    ) {
        runSourceTask(
            operation,
            "script path transition",
            work = {
                transition.apply()
                work()
            }
        ) { worked ->
            worked.fold(
                onSuccess = success,
                onFailure = { error ->
                    finishOperation(
                        operation,
                        result(
                            operation.operation,
                            ScriptOperationStatus.FAILED,
                            operation.affectedPaths,
                            listOf(
                                ScriptDiagnostic(
                                    transition.target.path,
                                    ScriptDiagnosticPhase.SOURCE,
                                    "Could not apply or read the script target: " +
                                        (error.message ?: error.javaClass.name)
                                )
                            )
                        )
                    )
                }
            )
        }
    }

    private fun dispatchOperationToMain(
        operation: PendingOperation,
        description: String,
        block: () -> Unit
    ) {
        runCatching {
            Bukkit.getScheduler().runTask(
                plugin,
                Runnable {
                    if (!isPending(operation) || shuttingDown) return@Runnable
                    runCatching(block).onFailure { error ->
                        finishOperation(operation, operationFailure(error, operation.operation))
                    }
                }
            )
        }.onFailure { error ->
            completeOperationWithoutMain(
                operation,
                result(
                    operation.operation,
                    ScriptOperationStatus.DISABLED,
                    operation.affectedPaths,
                    listOf(
                        ScriptDiagnostic(
                            operation.sourceTransition?.target?.path ?: "<target>",
                            ScriptDiagnosticPhase.SOURCE,
                            "Could not return $description to the main thread: ${error.message ?: error.javaClass.name}"
                        )
                    )
                )
            )
        }
    }

    private fun <T> runSourceTask(
        operation: PendingOperation,
        description: String,
        work: () -> T,
        schedulingFailure: (Throwable) -> ScriptOperationResult = { error ->
            operationFailure(error, operation.operation)
        },
        completion: (Result<T>) -> Unit
    ) {
        try {
            sourceExecutor.execute {
                val outcome = runCatching(work)
                if (shuttingDown) {
                    completeOperationWithoutMain(
                        operation,
                        result(operation.operation, ScriptOperationStatus.DISABLED, operation.affectedPaths)
                    )
                    return@execute
                }
                dispatchOperationToMain(operation, description) {
                    completion(outcome)
                }
            }
        } catch (error: Throwable) {
            finishOperation(operation, schedulingFailure(error))
        }
    }

    private fun readAllAsync(
        operation: PendingOperation,
        success: (List<LoadedScript>) -> Unit
    ) {
        runSourceTask(
            operation,
            "script source reading",
            work = { sources.all().map { source -> source.toLoadedScript() } },
            schedulingFailure = { error ->
                result(
                    operation.operation,
                    ScriptOperationStatus.DISABLED,
                    operation.affectedPaths,
                    listOf(
                        ScriptDiagnostic(
                            "<sources>",
                            ScriptDiagnosticPhase.SOURCE,
                            "Could not schedule script source reading: ${error.message ?: error.javaClass.name}"
                        )
                    )
                )
            }
        ) { loaded ->
            loaded.fold(
                onSuccess = success,
                onFailure = { error ->
                    finishOperation(
                        operation,
                        result(
                            operation.operation,
                            ScriptOperationStatus.FAILED,
                            operation.affectedPaths,
                            listOf(
                                ScriptDiagnostic(
                                    "<sources>",
                                    ScriptDiagnosticPhase.SOURCE,
                                    "Could not read script sources: ${error.message}"
                                )
                            )
                        )
                    )
                }
            )
        }
    }

    private fun finishOperation(operation: PendingOperation, operationResult: ScriptOperationResult) {
        if (!detachPending(operation)) return
        completeOperation(operation, operationResult)
    }

    private fun completeOperationWithoutMain(
        operation: PendingOperation,
        operationResult: ScriptOperationResult
    ) {
        if (!detachPending(operation)) return
        completeOperation(operation, operationResult)
    }

    private fun completeOperation(operation: PendingOperation, operationResult: ScriptOperationResult) {
        val finalized = finalizeSourceTransition(operation, operationResult)
        publishSnapshot()
        operation.future.complete(finalized)
    }

    private fun finalizeSourceTransition(
        operation: PendingOperation,
        operationResult: ScriptOperationResult
    ): ScriptOperationResult {
        val transition = operation.sourceTransition ?: return operationResult
        val normalizedResult = if (
            operationResult.status == ScriptOperationStatus.NO_CHANGE && transition.changed
        ) {
            operationResult.copy(
                status = ScriptOperationStatus.SUCCESS,
                affectedPaths = operationResult.affectedPaths.ifEmpty { listOf(transition.target.path) }
            )
        } else {
            operationResult
        }
        val successful = normalizedResult.status == ScriptOperationStatus.SUCCESS ||
            normalizedResult.status == ScriptOperationStatus.NO_CHANGE
        val finalized = runCatching {
            if (successful) transition.commit() else transition.rollback()
            normalizedResult
        }.getOrElse { error ->
            system(
                systemFeedback(
                    FeedbackLevel.ERROR,
                    FeedbackKey.SYSTEM_PATH_ROLLBACK_FAILED,
                    "target" to transition.target.path,
                    "error" to (error.message ?: error.javaClass.name),
                    cause = error
                )
            )
            normalizedResult.copy(
                status = ScriptOperationStatus.FAILED,
                diagnostics = normalizedResult.diagnostics + ScriptDiagnostic(
                    transition.target.path,
                    ScriptDiagnosticPhase.ROLLBACK,
                    "Could not restore the script path after the operation: ${error.message ?: error.javaClass.name}"
                )
            )
        }
        runCatching(sources::refreshKnownPaths).onFailure { error ->
            system(
                systemFeedback(
                    FeedbackLevel.WARNING,
                    FeedbackKey.SYSTEM_SUGGESTIONS_REFRESH_FAILED,
                    "error" to (error.message ?: error.javaClass.name)
                )
            )
        }
        return finalized
    }

    private fun isPending(operation: PendingOperation): Boolean = pending?.token == operation.token

    private fun detachPending(operation: PendingOperation): Boolean = synchronized(pendingLock) {
        if (pending?.token != operation.token) return@synchronized false
        pending = null
        true
    }

    private fun handleOutcome(outcome: ScriptCompilationOutcome) {
        assertMainThread()
        val current = pending
        try {
            handleOutcome(current, outcome)
        } catch (error: Throwable) {
            current?.let(::detachPending)
            runCatching { coordinator.reject(outcome) }
            if ((outcome as? ScriptCompilationOutcome.Success)?.generation !== generation.compiled) {
                runCatching { applier.closeOutcomeArtifacts(outcome) }
            }
            val diagnostic = ScriptDiagnostic(
                "<internal>",
                ScriptDiagnosticPhase.ACTIVATE,
                "Unexpected script operation failure: ${error.message ?: error.javaClass.name}"
            )
            system(
                systemFeedback(
                    FeedbackLevel.ERROR,
                    FeedbackKey.SYSTEM_OPERATION_UNEXPECTED,
                    "operation" to current?.operation?.feedbackText(),
                    "error" to (error.message ?: error.javaClass.name),
                    cause = error
                )
            )
            current?.takeUnless { operation -> operation.future.isDone }?.let { operation ->
                completeOperation(
                    operation,
                    result(
                        operation.operation,
                        ScriptOperationStatus.FAILED,
                        operation.affectedPaths,
                        listOf(diagnostic)
                    )
                )
            }
        }
    }

    private fun handleOutcome(current: PendingOperation?, outcome: ScriptCompilationOutcome) {
        if (current == null || current.requestId != outcome.request.id || shuttingDown) {
            runCatching { coordinator.reject(outcome) }
            applier.closeOutcomeArtifacts(outcome)
            return
        }
        if (!detachPending(current)) {
            runCatching { coordinator.reject(outcome) }
            applier.closeOutcomeArtifacts(outcome)
            return
        }

        val stillCurrent = coordinator.isCurrent(outcome.request) &&
            generation.revision == outcome.request.activeRevision &&
            generation.loaded.map(LoadedScript::source) == outcome.request.activeSources
        if (outcome !is ScriptCompilationOutcome.Cancelled && !stillCurrent) {
            coordinator.reject(outcome)
            applier.closeOutcomeArtifacts(outcome)
            completeOperation(
                current,
                result(current.operation, ScriptOperationStatus.CANCELLED, current.affectedPaths)
            )
            return
        }

        when (outcome) {
            is ScriptCompilationOutcome.Cancelled -> {
                completeOperation(
                    current,
                    result(current.operation, ScriptOperationStatus.CANCELLED, current.affectedPaths)
                )
            }
            is ScriptCompilationOutcome.Failure -> {
                coordinator.reject(outcome)
                outcome.environment?.let(ideEnvironment::publishEnvironmentIfChanged)
                val diagnostic = outcome.diagnostic.toScriptDiagnostic(ScriptDiagnosticPhase.COMPILE)
                logDiagnostic(diagnostic, outcome.diagnostic)
                completeOperation(
                    current,
                    result(
                        current.operation,
                        ScriptOperationStatus.FAILED,
                        current.affectedPaths,
                        listOf(diagnostic)
                    )
                )
            }
            is ScriptCompilationOutcome.Success -> {
                ideEnvironment.publishEnvironmentIfChanged(outcome.environment)
                if (outcome.affectedPaths.isEmpty()) {
                    coordinator.commit(outcome, generation.revision)
                    applier.closeOutcomeArtifacts(outcome)
                    completeOperation(
                        current,
                        result(current.operation, ScriptOperationStatus.NO_CHANGE, current.affectedPaths)
                    )
                    return
                }
                val candidate = outcome.candidateSources.map { source -> LoadedScript(source.name, source.text) }
                val applied = applier.apply(candidate, outcome)
                if (applied.success) {
                    coordinator.commit(outcome, generation.revision)
                    generation.compiled?.let { compiled ->
                        coordinator.publishCache(compiled, outcome.environment) { error ->
                            system(
                                systemFeedback(
                                    FeedbackLevel.WARNING,
                                    FeedbackKey.SYSTEM_CACHE_PUBLISH_FAILED,
                                    "error" to (error.message ?: error.javaClass.name)
                                )
                            )
                        }
                    }
                    completeOperation(
                        current,
                        result(current.operation, ScriptOperationStatus.SUCCESS, outcome.affectedPaths)
                    )
                } else {
                    coordinator.reject(outcome)
                    applied.diagnostics.forEach { diagnostic -> logDiagnostic(diagnostic) }
                    completeOperation(
                        current,
                        result(
                            current.operation,
                            ScriptOperationStatus.FAILED,
                            outcome.affectedPaths,
                            applied.diagnostics
                        )
                    )
                }
            }
        }
    }

    private data class StartupApply(
        val result: ScriptOperationResult,
        val cacheRetryable: Boolean
    )

    private fun applyStartup(
        candidate: List<LoadedScript>,
        outcome: ScriptCompilationOutcome
    ): StartupApply = when (outcome) {
        is ScriptCompilationOutcome.Cancelled -> StartupApply(
            result(ScriptOperation.RELOAD, ScriptOperationStatus.CANCELLED),
            false
        )
        is ScriptCompilationOutcome.Failure -> {
            outcome.environment?.let(ideEnvironment::publishEnvironmentIfChanged)
            val diagnostic = outcome.diagnostic.toScriptDiagnostic(ScriptDiagnosticPhase.COMPILE)
            logDiagnostic(diagnostic, outcome.diagnostic)
            StartupApply(
                result(
                    ScriptOperation.RELOAD,
                    ScriptOperationStatus.FAILED,
                    candidate.map(LoadedScript::name),
                    listOf(diagnostic)
                ),
                false
            )
        }
        is ScriptCompilationOutcome.Success -> {
            ideEnvironment.publishEnvironmentIfChanged(outcome.environment)
            val applied = applier.apply(candidate, outcome)
            if (applied.success) {
                coordinator.commit(outcome, generation.revision)
                if (!outcome.cacheHit) {
                    generation.compiled?.let { compiled ->
                        coordinator.publishCache(compiled, outcome.environment) { error ->
                            system(
                                systemFeedback(
                                    FeedbackLevel.WARNING,
                                    FeedbackKey.SYSTEM_CACHE_PUBLISH_FAILED,
                                    "error" to (error.message ?: error.javaClass.name)
                                )
                            )
                        }
                    }
                }
                StartupApply(
                    result(
                        ScriptOperation.RELOAD,
                        ScriptOperationStatus.SUCCESS,
                        outcome.affectedPaths
                    ),
                    false
                )
            } else {
                coordinator.reject(outcome)
                applied.diagnostics.forEach { diagnostic -> logDiagnostic(diagnostic) }
                StartupApply(
                    result(
                        ScriptOperation.RELOAD,
                        ScriptOperationStatus.FAILED,
                        candidate.map(LoadedScript::name),
                        applied.diagnostics
                    ),
                    applied.cacheRetryable
                )
            }
        }
    }

    private fun readAll(operation: ScriptOperation): Result<List<LoadedScript>> = runCatching {
        sources.all().map { source -> source.toLoadedScript() }
    }.recoverCatching { error ->
        throw ScriptSourceReadException(
            result(
                operation,
                ScriptOperationStatus.FAILED,
                diagnostics = listOf(
                    ScriptDiagnostic(
                        "<sources>",
                        ScriptDiagnosticPhase.SOURCE,
                        "Could not read script sources: ${error.message}"
                    )
                )
            )
        )
    }

    private fun captureEnvironment(operation: ScriptOperation): Result<ScriptEnvironmentSnapshot> = runCatching {
        ScriptCompilationEnvironmentFactory.capture(plugin, paths)
    }.recoverCatching { error ->
        throw ScriptSourceReadException(
            result(
                operation,
                ScriptOperationStatus.FAILED,
                diagnostics = listOf(
                    ScriptDiagnostic(
                        "<environment>",
                        ScriptDiagnosticPhase.COMPILE,
                        "Kotlin scripting environment capture failed: ${error.message}"
                    )
                )
            )
        )
    }

    private fun operationFailure(
        error: Throwable,
        operation: ScriptOperation
    ): ScriptOperationResult = (error as? ScriptSourceReadException)?.result ?: result(
        operation,
        ScriptOperationStatus.FAILED,
        diagnostics = listOf(
            ScriptDiagnostic(
                "<internal>",
                ScriptDiagnosticPhase.SOURCE,
                error.message ?: "Unknown failure"
            )
        )
    )

    private fun onMain(
        operation: ScriptOperation,
        block: (CompletableFuture<ScriptOperationResult>) -> Unit
    ): CompletionStage<ScriptOperationResult> = operationDispatcher.dispatch(
        operation,
        disabledResult = { disabled -> result(disabled, ScriptOperationStatus.DISABLED) }
    ) { future ->
        if (shuttingDown || engineState == ScriptEngineState.DISABLED) {
            future.complete(result(operation, ScriptOperationStatus.DISABLED))
        } else if (engineState == ScriptEngineState.STARTING) {
            future.complete(result(operation, ScriptOperationStatus.BUSY))
        } else {
            try {
                block(future)
            } catch (error: Throwable) {
                system(
                    systemFeedback(
                        FeedbackLevel.ERROR,
                        FeedbackKey.SYSTEM_OPERATION_UNEXPECTED,
                        "operation" to operation.feedbackText(),
                        "error" to (error.message ?: error.javaClass.name),
                        cause = error
                    )
                )
                val failure = result(
                    operation,
                    ScriptOperationStatus.FAILED,
                    diagnostics = listOf(
                        ScriptDiagnostic(
                            "<internal>",
                            ScriptDiagnosticPhase.ACTIVATE,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                )
                val started = pending?.takeIf { candidate -> candidate.future === future }
                if (started == null) future.complete(failure) else finishOperation(started, failure)
            }
        }
    }

    private fun cancelPending() {
        val operation = synchronized(pendingLock) {
            val current = pending ?: return
            pending = null
            current
        }
        completeOperation(
            operation,
            result(operation.operation, ScriptOperationStatus.CANCELLED, operation.affectedPaths)
        )
    }

    private fun rejectIfBusy(
        operation: ScriptOperation,
        future: CompletableFuture<ScriptOperationResult>
    ): Boolean {
        if (synchronized(pendingLock) { pending == null }) return false
        future.complete(result(operation, ScriptOperationStatus.BUSY))
        return true
    }

    private fun publishSnapshot() {
        publishedSnapshot.set(
            ScriptSnapshot(
                revision = generation.revision,
                state = engineState,
                busyOperation = pending?.operation,
                scripts = copyOf(generation.loaded.map { source -> ScriptInfo(source.name) })
            )
        )
    }

    private fun result(
        operation: ScriptOperation,
        status: ScriptOperationStatus,
        affectedPaths: List<String> = emptyList(),
        diagnostics: List<ScriptDiagnostic> = emptyList()
    ): ScriptOperationResult = operationReporter.result(operation, status, affectedPaths, diagnostics)

    private fun logDiagnostic(
        diagnostic: ScriptDiagnostic,
        internal: SharedReplDiagnostic? = null
    ) = operationReporter.logDiagnostic(diagnostic, internal)

    private fun ScriptSourceFile.toLoadedScript(): LoadedScript = LoadedScript(name, text)

    private fun assertMainThread() {
        check(Bukkit.isPrimaryThread()) { "Script runtime state may only be accessed from the Paper main thread" }
    }

    private class ScriptSourceReadException(val result: ScriptOperationResult) : RuntimeException()

    private companion object {
        val sourceComparator: Comparator<LoadedScript> = compareBy(
            { source -> source.name.lowercase(Locale.ROOT) },
            LoadedScript::name
        )
    }
}
