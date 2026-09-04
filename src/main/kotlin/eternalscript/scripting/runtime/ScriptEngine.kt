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
import eternalscript.messaging.MessageKey
import eternalscript.messaging.MessageLevel
import eternalscript.messaging.SystemMessage
import eternalscript.messaging.messageText
import eternalscript.messaging.systemMessage
import eternalscript.ide.EternalScriptIdeEnvironmentPublisher
import eternalscript.scripting.cache.ScriptCacheLayout
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
import java.util.concurrent.atomic.AtomicReference

internal class ScriptEngine(
    private val plugin: JavaPlugin,
    private val paths: PluginPaths,
    private val sources: ScriptSourceRepository,
    private val system: (SystemMessage) -> Unit,
    private val ideEnvironment: EternalScriptIdeEnvironmentPublisher,
    cacheEnabled: () -> Boolean = { true }
) {
    private val generation = ScriptGenerationState()
    private val commandRegistry = ScriptCommandRegistry(plugin, system)
    private val applier = ScriptGenerationApplier(plugin, generation, commandRegistry, system)
    private val operationDispatcher = ScriptOperationDispatcher(plugin)
    private val operationReporter = ScriptOperationReporter({ generation.revision }, system)
    private val operations = ScriptOperationTracker()
    private val sourceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EternalScript-Sources").apply {
            isDaemon = true
            contextClassLoader = ClassLoader.getSystemClassLoader()
        }
    }
    private val coordinator = ScriptCompilationCoordinator(
        plugin,
        ScriptCacheLayout.currentDirectory(paths.cacheDirectory),
        cacheEnabled
    )
    private val publishedSnapshot = AtomicReference(
        ScriptSnapshot(0, ScriptEngineState.STARTING, null, emptyList())
    )
    private var engineState = ScriptEngineState.STARTING
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
            val preparedEnvironment = coordinator.environmentBlocking(environment)
            preparedEnvironment.onSuccess(ideEnvironment::publishEnvironmentIfChanged)
            engineState = ScriptEngineState.READY
            publishSnapshot()
            return preparedEnvironment.fold(
                onSuccess = { result(ScriptOperation.RELOAD, ScriptOperationStatus.SUCCESS) },
                onFailure = { error ->
                    val diagnostic = ScriptDiagnostic(
                        "<environment>",
                        ScriptDiagnosticPhase.COMPILE,
                        "Kotlin scripting environment preparation failed: ${error.message ?: error.javaClass.name}"
                    )
                    logDiagnostic(diagnostic)
                    result(
                        ScriptOperation.RELOAD,
                        ScriptOperationStatus.FAILED,
                        diagnostics = listOf(diagnostic)
                    )
                }
            )
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

    fun reload(path: String? = null): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.RELOAD) { future ->
        if (path != null) {
            reloadTarget(path, future)
            return@onMain
        }
        val operation = beginOperation(ScriptOperation.RELOAD, future) ?: return@onMain
        readAllAsync(operation) { candidate ->
            submit(operation, candidate)
        }
    }

    fun check(path: String? = null): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.CHECK) { future ->
        val operation = beginOperation(ScriptOperation.CHECK, future, listOfNotNull(path)) ?: return@onMain
        readAllAsync(operation) { available ->
            if (path == null) {
                submit(operation, available, forceAll = true)
                return@readAllAsync
            }
            resolveTargetAsync(operation, { sources.prepareEnabled(path) }) { transition ->
                val replacements = available.filter { transition.target.contains(it.name) }
                if (replacements.isEmpty()) {
                    finishOperation(operation, result(ScriptOperation.CHECK, ScriptOperationStatus.NOT_FOUND, listOf(path)))
                    return@resolveTargetAsync
                }
                val universe = generation.loaded.associateByTo(linkedMapOf(), LoadedScript::name)
                replacements.forEach { universe[it.name] = it }
                submit(
                    operation,
                    universe.values.sortedWith(sourceComparator),
                    forceAll = true,
                    selection = ScriptCandidateSelection.Load(
                        replacements.mapTo(linkedSetOf(), LoadedScript::name),
                        generation.loaded.mapTo(linkedSetOf(), LoadedScript::name)
                    )
                )
            }
        }
    }

    fun recompile(): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.RECOMPILE) { future ->
        val operation = beginOperation(ScriptOperation.RECOMPILE, future) ?: return@onMain
        val candidate = generation.loaded.map(LoadedScript::withoutRuntime)
        submit(operation, candidate, forceAll = true)
    }

    private fun reloadTarget(path: String, future: CompletableFuture<ScriptOperationResult>) {
        val operation = beginOperation(ScriptOperation.RELOAD, future, listOf(path)) ?: return
        resolveTargetAsync(operation, { sources.prepareEnabled(path) }) { transition ->
            val activePaths = generation.loaded.filter { transition.target.contains(it.name) }
            if (activePaths.isEmpty()) {
                finishOperation(
                    operation,
                    result(ScriptOperation.RELOAD, ScriptOperationStatus.NOT_FOUND, listOf(transition.target.path))
                )
                return@resolveTargetAsync
            }
            readAllAsync(operation) { available ->
                val replacements = available.filter { transition.target.contains(it.name) }
                if (replacements.isEmpty()) {
                    finishOperation(
                        operation,
                        result(ScriptOperation.RELOAD, ScriptOperationStatus.NOT_FOUND, listOf(transition.target.path))
                    )
                    return@readAllAsync
                }
                val universe = generation.loaded.associateByTo(linkedMapOf(), LoadedScript::name)
                activePaths.forEach { universe.remove(it.name) }
                replacements.forEach { universe[it.name] = it }
                submit(
                    operation,
                    universe.values.sortedWith(sourceComparator),
                    selection = ScriptCandidateSelection.Load(
                        replacements.mapTo(linkedSetOf(), LoadedScript::name),
                        generation.loaded.mapTo(linkedSetOf(), LoadedScript::name)
                    )
                )
            }
        }
    }

    fun enable(path: String): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.ENABLE) { future ->
        if (sources.knownTargets().any { it.path == path && it.enabled }) {
            future.complete(result(ScriptOperation.ENABLE, ScriptOperationStatus.NO_CHANGE, listOf(path)))
            return@onMain
        }
        val operation = beginOperation(ScriptOperation.ENABLE, future, listOf(path)) ?: return@onMain
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
                        result(ScriptOperation.ENABLE, status, listOf(transition.target.path))
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

    fun disable(path: String): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.DISABLE) { future ->
        val operation = beginOperation(ScriptOperation.DISABLE, future, listOf(path)) ?: return@onMain
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
                        ScriptOperation.DISABLE,
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
                        ScriptOperation.DISABLE,
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
                        result(ScriptOperation.DISABLE, status, listOf(transition.target.path))
                    )
                } else {
                    submit(operation, candidate)
                }
            }
        }
    }

    fun cancel(): CompletionStage<ScriptOperationResult> = onMain(ScriptOperation.CANCEL) { future ->
        when (val cancelled = operations.cancelCancellable()) {
            CancelOperationResult.Idle -> future.complete(result(ScriptOperation.CANCEL, ScriptOperationStatus.NO_CHANGE))
            CancelOperationResult.Busy -> future.complete(result(ScriptOperation.CANCEL, ScriptOperationStatus.BUSY))
            is CancelOperationResult.Cancelled -> {
                coordinator.invalidate()
                publishSnapshot()
                completeDetachedOperation(
                    cancelled.operation,
                    result(
                        cancelled.operation.operation,
                        ScriptOperationStatus.CANCELLED,
                        cancelled.operation.affectedPaths
                    )
                )
                future.complete(
                    result(ScriptOperation.CANCEL, ScriptOperationStatus.SUCCESS, cancelled.operation.affectedPaths)
                )
            }
        }
    }

    fun shutdown() {
        assertMainThread()
        if (shuttingDown) return
        shuttingDown = true
        engineState = ScriptEngineState.DISABLED
        operationDispatcher.close(::disabledSnapshotResult)
        cancelPending()
        sourceExecutor.shutdownNow()
        runCatching { sourceExecutor.awaitTermination(5, TimeUnit.SECONDS) }
        coordinator.invalidate(environment = true)
        applier.shutdown()
        coordinator.close()
        publishSnapshot()
    }

    private fun submit(
        operation: PendingScriptOperation,
        candidate: List<LoadedScript>,
        forceAll: Boolean = false,
        selection: ScriptCandidateSelection = ScriptCandidateSelection.Exact
    ) {
        if (!operations.isCurrent(operation) || shuttingDown) return
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
        if (!operations.markCompiling(operation, request.id)) return
        publishSnapshot()
        coordinator.compileAsync(
            request,
            dispatchFailure = { error ->
                deferOperationCompletion(
                    operation,
                    workerResult(
                        operation,
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
    ): PendingScriptOperation? {
        val started = operations.tryBegin(operation, future, requestedPaths)
        if (started == null) {
            future.complete(result(operation, ScriptOperationStatus.BUSY))
        } else {
            publishSnapshot()
        }
        return started
    }

    private fun resolveTargetAsync(
        operation: PendingScriptOperation,
        resolve: () -> ScriptTargetPreparation,
        success: (ScriptPathTransition) -> Unit
    ) {
        runSourceTask(operation, "script target resolution", resolve) { prepared ->
            prepared.fold(
                onSuccess = { target ->
                    when (target) {
                        is ScriptTargetPreparation.Ready -> {
                            if (!operations.attachTransition(operation, target.transition)) {
                                target.transition.rollback()
                                return@fold
                            }
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
        operation: PendingScriptOperation,
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
        operation: PendingScriptOperation,
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
            deferOperationCompletion(
                operation,
                workerResult(
                    operation,
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
        operation: PendingScriptOperation,
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
                    deferOperationCompletion(
                        operation,
                        workerResult(operation, ScriptOperationStatus.DISABLED, operation.affectedPaths)
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
        operation: PendingScriptOperation,
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

    private fun finishOperation(
        operation: PendingScriptOperation,
        operationResult: ScriptOperationResult
    ) {
        if (!operations.detach(operation)) return
        completeDetachedOperation(operation, operationResult)
    }

    private fun deferOperationCompletion(
        operation: PendingScriptOperation,
        operationResult: ScriptOperationResult
    ) {
        val finalized = operations.finishFromWorker(
            operation,
            operationResult,
            finalize = { terminal ->
                // The source worker owns path I/O. Roll back an applied rename before exposing a
                // terminal result when Paper can no longer dispatch the callback to its main thread.
                finalizeSourceTransition(operation, terminal)
            },
            publishDetachedState = {
                publishedSnapshot.updateAndGet { snapshot ->
                    if (snapshot.busyOperation == null) snapshot else snapshot.copy(busyOperation = null)
                }
            }
        ) ?: return
        operation.future.complete(finalized)
    }

    private fun completeDetachedOperation(
        operation: PendingScriptOperation,
        operationResult: ScriptOperationResult
    ) {
        val finalized = finalizeSourceTransition(operation, operationResult)
        publishSnapshot()
        operation.future.complete(finalized)
    }

    private fun finalizeSourceTransition(
        operation: PendingScriptOperation,
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
            runCatching {
                system(
                    systemMessage(
                        MessageLevel.ERROR,
                        MessageKey.SYSTEM_PATH_ROLLBACK_FAILED,
                        "target" to transition.target.path,
                        "error" to (error.message ?: error.javaClass.name),
                        cause = error
                    )
                )
            }
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
            runCatching {
                system(
                    systemMessage(
                        MessageLevel.WARNING,
                        MessageKey.SYSTEM_SUGGESTIONS_REFRESH_FAILED,
                        "error" to (error.message ?: error.javaClass.name)
                    )
                )
            }
        }
        return finalized
    }

    private fun isPending(operation: PendingScriptOperation): Boolean = operations.isCurrent(operation)

    private fun handleOutcome(outcome: ScriptCompilationOutcome) {
        assertMainThread()
        val current = operations.current()
        val matching = current?.takeIf { operation -> operation.requestId == outcome.request.id }
        try {
            handleOutcome(current, outcome)
        } catch (error: Throwable) {
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
                systemMessage(
                    MessageLevel.ERROR,
                    MessageKey.SYSTEM_OPERATION_UNEXPECTED,
                    "operation" to matching?.operation?.messageText(),
                    "error" to (error.message ?: error.javaClass.name),
                    cause = error
                )
            )
            matching?.takeUnless { operation -> operation.future.isDone }?.let { operation ->
                finishOperation(
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

    private fun handleOutcome(current: PendingScriptOperation?, outcome: ScriptCompilationOutcome) {
        if (
            current == null ||
            current.requestId != outcome.request.id ||
            shuttingDown ||
            !operations.markApplying(current, outcome.request.id)
        ) {
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
            finishOperation(
                current,
                result(current.operation, ScriptOperationStatus.CANCELLED, current.affectedPaths)
            )
            return
        }

        when (outcome) {
            is ScriptCompilationOutcome.Cancelled -> {
                finishOperation(
                    current,
                    result(current.operation, ScriptOperationStatus.CANCELLED, current.affectedPaths)
                )
            }
            is ScriptCompilationOutcome.Failure -> {
                coordinator.reject(outcome)
                outcome.environment?.let(ideEnvironment::publishEnvironmentIfChanged)
                val diagnostic = outcome.diagnostic.toScriptDiagnostic(ScriptDiagnosticPhase.COMPILE)
                logDiagnostic(diagnostic, outcome.diagnostic)
                finishOperation(
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
                if (current.operation == ScriptOperation.CHECK) {
                    coordinator.reject(outcome)
                    applier.closeOutcomeArtifacts(outcome)
                    finishOperation(
                        current,
                        result(
                            ScriptOperation.CHECK,
                            ScriptOperationStatus.SUCCESS,
                            outcome.affectedPaths.ifEmpty { current.affectedPaths }
                        )
                    )
                    return
                }
                if (outcome.affectedPaths.isEmpty()) {
                    coordinator.commit(outcome, generation.revision)
                    applier.closeOutcomeArtifacts(outcome)
                    finishOperation(
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
                                systemMessage(
                                    MessageLevel.WARNING,
                                    MessageKey.SYSTEM_CACHE_PUBLISH_FAILED,
                                    "error" to (error.message ?: error.javaClass.name)
                                )
                            )
                        }
                    }
                    finishOperation(
                        current,
                        result(current.operation, ScriptOperationStatus.SUCCESS, outcome.affectedPaths)
                    )
                } else {
                    coordinator.reject(outcome)
                    applied.diagnostics.forEach { diagnostic -> logDiagnostic(diagnostic) }
                    finishOperation(
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
                                systemMessage(
                                    MessageLevel.WARNING,
                                    MessageKey.SYSTEM_CACHE_PUBLISH_FAILED,
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
        disabledResult = ::disabledSnapshotResult
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
                    systemMessage(
                        MessageLevel.ERROR,
                        MessageKey.SYSTEM_OPERATION_UNEXPECTED,
                        "operation" to operation.messageText(),
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
                val started = operations.findByFuture(future)
                if (started == null) future.complete(failure) else finishOperation(started, failure)
            }
        }
    }

    private fun cancelPending() {
        val operation = operations.cancelCurrent() ?: return
        completeDetachedOperation(
            operation,
            result(operation.operation, ScriptOperationStatus.CANCELLED, operation.affectedPaths)
        )
    }

    private fun publishSnapshot() {
        publishedSnapshot.set(
            ScriptSnapshot(
                revision = generation.revision,
                state = engineState,
                busyOperation = operations.current()?.operation,
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

    /** Builds an off-main terminal result without reading the main-thread generation state. */
    private fun disabledSnapshotResult(operation: ScriptOperation): ScriptOperationResult = snapshotResult(
        operation,
        ScriptOperationStatus.DISABLED
    )

    /** Builds a worker-owned terminal result without reading the main-thread generation state. */
    private fun workerResult(
        operation: PendingScriptOperation,
        status: ScriptOperationStatus,
        affectedPaths: List<String> = emptyList(),
        diagnostics: List<ScriptDiagnostic> = emptyList()
    ): ScriptOperationResult = snapshotResult(operation.operation, status, affectedPaths, diagnostics)

    private fun snapshotResult(
        operation: ScriptOperation,
        status: ScriptOperationStatus,
        affectedPaths: List<String> = emptyList(),
        diagnostics: List<ScriptDiagnostic> = emptyList()
    ): ScriptOperationResult = ScriptOperationResult(
        operation,
        status,
        publishedSnapshot.get().revision,
        copyOf(affectedPaths),
        copyOf(diagnostics)
    )

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
