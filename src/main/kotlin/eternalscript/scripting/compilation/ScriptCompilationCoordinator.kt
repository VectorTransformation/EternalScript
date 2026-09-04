package eternalscript.scripting.compilation

import eternalscript.scripting.cache.ComponentArtifactCache
import eternalscript.scripting.cache.ComponentCacheLookup
import eternalscript.scripting.dependency.ScriptLoadPlan
import eternalscript.scripting.dependency.planScriptLoad
import eternalscript.scripting.repl.SharedReplDiagnostic
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.repl.k2.CompiledComponentGeneration
import eternalscript.scripting.repl.k2.BatchAnalysisResult
import eternalscript.scripting.repl.k2.BatchK2Compiler
import eternalscript.scripting.repl.k2.ComponentCompilationResult
import eternalscript.scripting.repl.k2.ScriptComponentCompiler
import eternalscript.scripting.repl.k2.batchScriptingHostConfiguration
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private fun paperMainDispatcher(plugin: JavaPlugin): ((() -> Unit) -> Unit) = { block ->
    Bukkit.getScheduler().runTask(plugin, Runnable(block))
}

internal class ScriptCompilationCoordinator(
    cacheRoot: File,
    compilerExecutor: ExecutorService? = null,
    cacheExecutor: ExecutorService? = null,
    private val closeTimeoutMillis: Long = 5_000,
    private val cacheEnabled: () -> Boolean = { true },
    private val mainDispatcher: ((() -> Unit) -> Unit)
) : AutoCloseable {
    constructor(
        plugin: JavaPlugin,
        cacheRoot: File,
        cacheEnabled: () -> Boolean = { true }
    ) : this(cacheRoot, cacheEnabled = cacheEnabled, mainDispatcher = paperMainDispatcher(plugin))

    private val operationSequence = AtomicLong()
    private val epoch = AtomicLong()
    private val closed = AtomicBoolean()
    private val compilerExecutor = compilerExecutor
        ?: Executors.newSingleThreadExecutor(scriptThreadFactory("EternalScript-Compiler"))
    private val cacheExecutor = cacheExecutor
        ?: Executors.newSingleThreadExecutor(scriptThreadFactory("EternalScript-Cache"))
    private val artifactRoot = File(cacheRoot, "live-components").toPath()
    private val artifactCache = ComponentArtifactCache(cacheRoot, artifactRoot)

    // Compiler-thread confined state. Runtime owns a separate retained reference.
    private var compilerGeneration: CompiledComponentGeneration? = null
    private var compilerEnvironment: ScriptCompilationEnvironment? = null
    private var compilerSources: List<SharedReplSource> = emptyList()
    private var compilerSourceDigest: String = sourceChainDigest(emptyList())
    private var compilerRevision: Long = -1
    private var pendingOperation: Long? = null
    private var cachedEnvironment: ScriptCompilationEnvironment? = null
    private var cachedEnvironmentSnapshot: ScriptEnvironmentSnapshot? = null
    private var cachedLibraryFingerprint: String? = null

    fun request(
        activeRevision: Long,
        activeSources: List<SharedReplSource>,
        candidateSources: List<SharedReplSource>,
        selection: ScriptCandidateSelection = ScriptCandidateSelection.Exact,
        environmentSnapshot: ScriptEnvironmentSnapshot,
        allowStartupCache: Boolean = false,
        forceAll: Boolean = false
    ): ScriptCompilationRequest = ScriptCompilationRequest(
        id = operationSequence.incrementAndGet(),
        epoch = epoch.get(),
        activeRevision = activeRevision,
        activeSources = activeSources,
        candidateSources = candidateSources,
        selection = selection,
        environmentSnapshot = environmentSnapshot,
        allowStartupCache = allowStartupCache,
        forceAll = forceAll
    )

    fun compileBlocking(request: ScriptCompilationRequest): ScriptCompilationOutcome =
        compilerExecutor.submit<ScriptCompilationOutcome> { safeCompile(request) }.get()

    fun environmentBlocking(snapshot: ScriptEnvironmentSnapshot): Result<ScriptCompilationEnvironment> =
        compilerExecutor.submit<Result<ScriptCompilationEnvironment>> {
            runCatching { environmentFor(snapshot) }
        }.get()

    fun compileAsync(
        request: ScriptCompilationRequest,
        dispatchFailure: (Throwable) -> Unit = {},
        callback: (ScriptCompilationOutcome) -> Unit
    ) {
        compilerExecutor.execute {
            val outcome = safeCompile(request)
            if (closed.get()) {
                discardOutcome(outcome)
                return@execute
            }
            try {
                dispatchMain {
                    if (closed.get()) discardOutcome(outcome) else callback(outcome)
                }
            } catch (error: Throwable) {
                if (pendingOperation == request.id) pendingOperation = null
                discardOutcome(outcome)
                runCatching { dispatchFailure(error) }
            }
        }
    }

    private fun safeCompile(request: ScriptCompilationRequest): ScriptCompilationOutcome = try {
        compile(request)
    } catch (error: Throwable) {
        ScriptCompilationOutcome.Failure(
            request,
            SharedReplDiagnostic(
                "<compiler>",
                "Unexpected compiler coordinator failure: ${error.message ?: error.javaClass.name}",
                cause = error
            ),
            emptyMetrics(0, "unavailable")
        )
    }

    fun commit(outcome: ScriptCompilationOutcome.Success, revision: Long) {
        val retained = outcome.generation.retained()
        val task = OwnedExecutorTask(
            runAction = {
                if (pendingOperation != outcome.request.id || outcome.request.epoch != epoch.get()) {
                    if (pendingOperation == outcome.request.id) pendingOperation = null
                    runCatching(retained::close)
                } else {
                    val previousGeneration = compilerGeneration
                    compilerGeneration = retained
                    compilerEnvironment = outcome.environment
                    compilerSources = outcome.candidateSources
                    compilerSourceDigest = sourceChainDigest(outcome.candidateSources)
                    compilerRevision = revision
                    pendingOperation = null
                    runCatching { previousGeneration?.close() }
                }
            },
            discardAction = retained::close
        )
        try {
            compilerExecutor.execute(task)
        } catch (error: Throwable) {
            task.discard()?.let { cleanupFailure ->
                if (cleanupFailure !== error) error.addSuppressed(cleanupFailure)
            }
            throw error
        }
    }

    fun reject(outcome: ScriptCompilationOutcome) {
        compilerExecutor.execute {
            if (pendingOperation == outcome.request.id) pendingOperation = null
        }
    }

    fun publishCache(
        generation: CompiledComponentGeneration,
        environment: ScriptCompilationEnvironment,
        reportFailure: (Throwable) -> Unit
    ) {
        if (closed.get() || !cacheEnabled()) return
        val retained = try {
            generation.retained()
        } catch (error: Throwable) {
            reportCacheFailure(error, reportFailure)
            return
        }
        val task = OwnedExecutorTask(
            runAction = {
                var failure: Throwable? = null
                try {
                    if (cacheEnabled()) {
                        withContextClassLoader(environment.baseClassLoader) {
                            artifactCache.publish(retained, environment.fingerprint)
                        }
                    }
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    runCatching(retained::close).exceptionOrNull()?.let { cleanupFailure ->
                        val primary = failure
                        if (primary == null) {
                            failure = cleanupFailure
                        } else if (primary !== cleanupFailure) {
                            primary.addSuppressed(cleanupFailure)
                        }
                    }
                    failure?.let { error -> reportCacheFailure(error, reportFailure) }
                }
            },
            discardAction = retained::close
        )
        try {
            cacheExecutor.execute(task)
        } catch (error: Throwable) {
            task.discard()?.let { cleanupFailure ->
                if (cleanupFailure !== error) error.addSuppressed(cleanupFailure)
            }
            reportCacheFailure(error, reportFailure)
        }
    }

    fun invalidate(environment: Boolean = false): Long {
        val next = epoch.incrementAndGet()
        compilerExecutor.execute {
            discardCompilerGeneration()
            if (environment) discardEnvironment()
        }
        return next
    }

    fun isCurrent(request: ScriptCompilationRequest): Boolean = request.epoch == epoch.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        epoch.incrementAndGet()
        val cleanupFinished = AtomicBoolean()
        val finishAfterTermination = {
            if (cleanupFinished.compareAndSet(false, true)) {
                discardCompilerGeneration()
                discardEnvironment()
                // CompiledComponentArtifact owns its JAR lifetime. A runtime callback may still hold
                // a retained artifact after the engine has retired its generation, so only remove an
                // already-empty root here.
                runCatching { Files.deleteIfExists(artifactRoot) }
            }
        }
        compilerExecutor.shutdown()
        cacheExecutor.shutdown()
        if (!awaitCloseExecutors(closeTimeoutMillis)) {
            val droppedCompilerTasks = runCatching(compilerExecutor::shutdownNow).getOrDefault(emptyList())
            val droppedCacheTasks = runCatching(cacheExecutor::shutdownNow).getOrDefault(emptyList())
            discardQueuedTasks(droppedCompilerTasks)
            discardQueuedTasks(droppedCacheTasks)
            if (!awaitCloseExecutors(closeTimeoutMillis)) {
                Thread(
                    {
                        awaitTerminationUninterruptibly(compilerExecutor)
                        awaitTerminationUninterruptibly(cacheExecutor)
                        finishAfterTermination()
                    },
                    "EternalScript-Shutdown-Cleanup"
                ).apply {
                    isDaemon = true
                    contextClassLoader = ClassLoader.getSystemClassLoader()
                    start()
                }
                return
            }
        }
        finishAfterTermination()
    }

    private fun compile(request: ScriptCompilationRequest): ScriptCompilationOutcome =
        withContextClassLoader(request.environmentSnapshot.baseClassLoader) {
            if (request.epoch != epoch.get()) return@withContextClassLoader ScriptCompilationOutcome.Cancelled(request)

            val environmentStarted = System.nanoTime()
            val environment = try {
                environmentFor(request.environmentSnapshot)
            } catch (error: Throwable) {
                if (request.epoch != epoch.get()) return@withContextClassLoader ScriptCompilationOutcome.Cancelled(request)
                return@withContextClassLoader ScriptCompilationOutcome.Failure(
                    request,
                    SharedReplDiagnostic(
                        "<environment>",
                        "Kotlin scripting environment failed: ${error.message}",
                        cause = error
                    ),
                    emptyMetrics(elapsedMillis(environmentStarted), "unavailable")
                )
            }
            val environmentMillis = elapsedMillis(environmentStarted)
            if (
                cacheEnabled() &&
                request.allowStartupCache &&
                request.activeSources.isEmpty() &&
                request.selection == ScriptCandidateSelection.Exact
            ) {
                when (val cached = artifactCache.lookup(request.candidateSources, environment.fingerprint)) {
                    is ComponentCacheLookup.Hit -> {
                        if (request.epoch != epoch.get()) {
                            runCatching(cached.generation::close)
                            return@withContextClassLoader ScriptCompilationOutcome.Cancelled(request)
                        }
                        pendingOperation = request.id
                        return@withContextClassLoader ScriptCompilationOutcome.Success(
                            request,
                            environment,
                            cached.generation,
                            request.candidateSources,
                            cached.generation.graph.initializationOrder,
                            cacheHit = true,
                            metrics = ScriptCompilationMetrics(
                                environmentMillis,
                                0,
                                0,
                                0,
                                0,
                                cached.generation.graph.components.size,
                                "hit"
                            )
                        )
                    }
                    is ComponentCacheLookup.Miss -> Unit
                }
            }
            val aligned = compilerGeneration != null &&
                compilerRevision == request.activeRevision &&
                compilerEnvironment === environment &&
                compilerSourceDigest == sourceChainDigest(request.activeSources) &&
                compilerSources == request.activeSources
            val previous = compilerGeneration.takeIf { aligned }
            val forceAll = request.forceAll || !aligned
            if (
                !forceAll &&
                request.selection == ScriptCandidateSelection.Exact &&
                request.candidateSources == compilerSources
            ) {
                val unchanged = checkNotNull(previous).retained()
                if (request.epoch != epoch.get()) {
                    runCatching(unchanged::close)
                    return@withContextClassLoader ScriptCompilationOutcome.Cancelled(request)
                }
                pendingOperation = request.id
                return@withContextClassLoader ScriptCompilationOutcome.Success(
                    request,
                    environment,
                    unchanged,
                    request.candidateSources,
                    emptyList(),
                    cacheHit = false,
                    metrics = ScriptCompilationMetrics(
                        environmentMillis,
                        0,
                        0,
                        0,
                        request.candidateSources.size,
                        unchanged.graph.components.size,
                        "miss"
                    )
                )
            }
            val compileStarted = System.nanoTime()
            val prepared = when (val selection = request.selection) {
                ScriptCandidateSelection.Exact -> PreparedCandidate(
                    request.candidateSources,
                    null,
                    emptySet(),
                    request.candidateSources.size
                )
                is ScriptCandidateSelection.Load -> {
                    val analysis = BatchK2Compiler(
                        environment.compilationConfiguration,
                        batchScriptingHostConfiguration(baseClassLoader = environment.baseClassLoader)
                    ).use { compiler -> compiler.analyze(request.candidateSources) }
                    val graph = when (analysis) {
                        is BatchAnalysisResult.Success -> analysis.graph
                        is BatchAnalysisResult.Failure -> return@withContextClassLoader ScriptCompilationOutcome.Failure(
                            request,
                            analysis.diagnostic,
                            emptyMetrics(environmentMillis, "miss").copy(
                                compileMillis = elapsedMillis(compileStarted),
                                analyzedCount = request.candidateSources.size
                            ),
                            environment
                        )
                    }
                    val loadPlan = planScriptLoad(graph, selection.targetPaths, selection.activePaths)
                    if (loadPlan is ScriptLoadPlan.MissingPaths) {
                        val missing = loadPlan.targetPaths + loadPlan.activePaths
                        val categories = buildList {
                            if (loadPlan.targetPaths.isNotEmpty()) {
                                add("target(s): ${loadPlan.targetPaths.sorted().joinToString()}")
                            }
                            if (loadPlan.activePaths.isNotEmpty()) {
                                add("active script(s): ${loadPlan.activePaths.sorted().joinToString()}")
                            }
                        }
                        return@withContextClassLoader ScriptCompilationOutcome.Failure(
                            request,
                            SharedReplDiagnostic(
                                missing.first(),
                                "Script path(s) disappeared before dependency planning (${categories.joinToString("; ")})"
                            ),
                            emptyMetrics(environmentMillis, "miss").copy(
                                compileMillis = elapsedMillis(compileStarted),
                                analyzedCount = request.candidateSources.size
                            ),
                            environment
                        )
                    }
                    val selectedPaths = (loadPlan as ScriptLoadPlan.Ready).selectedPaths
                    val selectedSources = request.candidateSources.filter { source -> source.name in selectedPaths }
                    PreparedCandidate(
                        selectedSources,
                        graph.induced(selectedPaths),
                        selection.targetPaths,
                        request.candidateSources.size
                    )
                }
            }
            val result = ScriptComponentCompiler(
                environment.compilationConfiguration,
                artifactRoot,
                environment.baseClassLoader
            ).compile(
                candidateSources = prepared.sources,
                previous = previous,
                forceAll = forceAll,
                forcedPaths = prepared.forcedPaths,
                analyzedGraph = prepared.graph,
                analyzedCount = prepared.analyzedCount
            )
            if (request.epoch != epoch.get()) {
                if (result is ComponentCompilationResult.Success) runCatching(result.generation::close)
                return@withContextClassLoader ScriptCompilationOutcome.Cancelled(request)
            }
            when (result) {
                is ComponentCompilationResult.Failure -> ScriptCompilationOutcome.Failure(
                        request,
                        result.diagnostic,
                        emptyMetrics(environmentMillis, "miss").copy(
                            compileMillis = elapsedMillis(compileStarted),
                            analyzedCount = prepared.analyzedCount
                        ),
                        environment
                )
                is ComponentCompilationResult.Success -> {
                    pendingOperation = request.id
                    ScriptCompilationOutcome.Success(
                        request,
                        environment,
                        result.generation,
                        prepared.sources,
                        result.affectedPaths,
                        cacheHit = false,
                        metrics = ScriptCompilationMetrics(
                            environmentMillis,
                            elapsedMillis(compileStarted),
                            result.metrics.analyzed,
                            result.metrics.compiled,
                            result.metrics.reused,
                            result.metrics.components,
                            "miss"
                        )
                    )
                }
            }
        }

    private data class PreparedCandidate(
        val sources: List<SharedReplSource>,
        val graph: eternalscript.scripting.repl.k2.ScriptDependencyGraph?,
        val forcedPaths: Set<String>,
        val analyzedCount: Int
    )

    private fun discardCompilerGeneration() {
        runCatching { compilerGeneration?.close() }
        compilerGeneration = null
        compilerEnvironment = null
        compilerSources = emptyList()
        compilerSourceDigest = sourceChainDigest(emptyList())
        compilerRevision = -1
        pendingOperation = null
    }

    private fun environmentFor(snapshot: ScriptEnvironmentSnapshot): ScriptCompilationEnvironment {
        val current = cachedEnvironment
        val libraryFingerprint = ScriptCompilationEnvironmentFactory.libraryFingerprint(snapshot)
        if (
            current != null &&
            cachedEnvironmentSnapshot?.hasSameInputs(snapshot) == true &&
            cachedLibraryFingerprint == libraryFingerprint
        ) {
            return current
        }

        val built = ScriptCompilationEnvironmentFactory.build(snapshot)
        cachedEnvironmentSnapshot = snapshot.copy(
            pluginClassLoaders = snapshot.pluginClassLoaders.toList(),
            libraryRoots = snapshot.libraryRoots.toList(),
            compilerRuntimeRoots = snapshot.compilerRuntimeRoots.toList()
        )
        cachedLibraryFingerprint = libraryFingerprint
        if (
            current?.fingerprint == built.fingerprint &&
            current.hasSameClassLoaderIdentity(built)
        ) {
            runCatching(built::close)
            return current
        }
        cachedEnvironment = built
        runCatching { current?.close() }
        return built
    }

    private fun discardEnvironment() {
        runCatching { cachedEnvironment?.close() }
        cachedEnvironment = null
        cachedEnvironmentSnapshot = null
        cachedLibraryFingerprint = null
    }

    private fun dispatchMain(block: () -> Unit) {
        mainDispatcher(block)
    }

    private fun reportCacheFailure(error: Throwable, reportFailure: (Throwable) -> Unit) {
        if (closed.get()) return
        runCatching {
            dispatchMain {
                if (!closed.get()) runCatching { reportFailure(error) }
            }
        }
    }

    private fun awaitCloseExecutors(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        if (!awaitTermination(compilerExecutor, deadline)) return false
        return awaitTermination(cacheExecutor, deadline)
    }

    private fun awaitTermination(executor: ExecutorService, deadline: Long): Boolean {
        if (executor.isTerminated) return true
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
        return try {
            executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun awaitTerminationUninterruptibly(executor: ExecutorService) {
        var interrupted = false
        while (!executor.isTerminated) {
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun discardQueuedTasks(tasks: List<Runnable>) {
        tasks.forEach { task ->
            when (task) {
                is OwnedExecutorTask -> task.discard()
                is Future<*> -> runCatching { task.cancel(false) }
            }
        }
    }

    private fun discardOutcome(outcome: ScriptCompilationOutcome) {
        if (outcome is ScriptCompilationOutcome.Success) runCatching(outcome.generation::close)
    }

    private fun <T> withContextClassLoader(classLoader: ClassLoader, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun emptyMetrics(environmentMillis: Long, cache: String): ScriptCompilationMetrics =
        ScriptCompilationMetrics(environmentMillis, 0, 0, 0, 0, 0, cache)

    private fun elapsedMillis(started: Long): Long = max(0L, (System.nanoTime() - started) / 1_000_000)

    private fun scriptThreadFactory(name: String) = ThreadFactory { runnable ->
        Thread(runnable, name).apply {
            isDaemon = true
            contextClassLoader = ClassLoader.getSystemClassLoader()
        }
    }
}

private fun ScriptEnvironmentSnapshot.hasSameInputs(other: ScriptEnvironmentSnapshot): Boolean =
    baseClassLoader === other.baseClassLoader &&
        pluginClassLoaders.size == other.pluginClassLoaders.size &&
        pluginClassLoaders.indices.all { index -> pluginClassLoaders[index] === other.pluginClassLoaders[index] } &&
        libraryRoots == other.libraryRoots &&
        pluginVersion == other.pluginVersion &&
        pluginArtifact == other.pluginArtifact &&
        compilerRuntimeRoots == other.compilerRuntimeRoots

private class OwnedExecutorTask(
    private val runAction: () -> Unit,
    private val discardAction: () -> Unit
) : Runnable {
    private val claimed = AtomicBoolean()

    override fun run() {
        if (claimed.compareAndSet(false, true)) runAction()
    }

    fun discard(): Throwable? {
        if (!claimed.compareAndSet(false, true)) return null
        return runCatching(discardAction).exceptionOrNull()
    }
}
