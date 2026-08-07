package eternalScript.core.runtime

import eternalScript.EternalScript
import eternalScript.core.command.MainCommand
import eternalScript.core.data.Config
import eternalScript.core.environment.ScriptEnvironmentCoordinator
import eternalScript.core.feedback.LocaleCatalog
import eternalScript.core.feedback.UserFeedbackChannels
import eternalScript.core.manager.ConfigManager
import eternalScript.core.manager.DataManager
import eternalScript.core.manager.MetricsService
import eternalScript.core.manager.ReloadManager
import eternalScript.core.manager.ScriptManager
import eternalScript.core.script.classloading.ScriptGenerationRegistry
import eternalScript.core.script.classpath.ScriptPluginClasspathRegistry
import eternalScript.core.script.definition.ScriptCompilationCache
import eternalScript.core.script.definition.libraryClasspath
import eternalScript.core.script.definition.scriptRuntimeClasspath
import eternalScript.core.script.generation.GenerationDiagnostics
import eternalScript.core.script.generation.GenerationStager
import eternalScript.core.script.generation.GenerationStateStore
import eternalScript.core.script.generation.GenerationRetirementService
import eternalScript.core.script.generation.GenerationLifecycleEngine
import eternalScript.core.script.generation.ScriptGenerationCoordinator
import eternalScript.core.script.project.KotlinProjectBackend
import eternalScript.core.script.project.ScriptGenerationEvaluator
import eternalScript.core.workspace.WorkspaceBootstrap
import eternalScript.core.workspace.WorkspaceManager

/** Owns every stateful service for exactly one plugin enable/disable lifecycle. */
internal class PluginRuntime(plugin: EternalScript) {
    private val lifecycleLock = Any()
    private var state = State.NEW

    private val host = PluginHost(plugin)
    private val server = ServerAccess(host)
    private val globalExecution = GlobalExecution(server)
    private val config = ConfigManager(host.paths)
    private val locales = LocaleCatalog(host, host.paths, config)
    private val feedback = UserFeedbackChannels(config, locales, host.logger)
    private val workspace = WorkspaceManager()
    private val bootstrap = WorkspaceBootstrap(host, host.paths, workspace)
    private val reloadManager = ReloadManager(config, locales)
    private val compilationCache = ScriptCompilationCache(host.paths.cache.file)
    private val classpathRegistry = ScriptPluginClasspathRegistry(
        runtimeClass = plugin.javaClass,
        libraryClasspath = { libraryClasspath(config, host.paths) }
    )
    private val generationRegistry = ScriptGenerationRegistry()
    private val environment = ScriptEnvironmentCoordinator(
        server,
        classpathRegistry,
        workspace,
        bootstrap
    )
    private val evaluator = ScriptGenerationEvaluator(
        host,
        server,
        globalExecution,
        compilationCache,
        generationRegistry
    )
    private val backend = KotlinProjectBackend(
        cacheRoot = {
            host.paths.cache.toPath().resolve(compilationCache.generation())
        },
        runtimeClasspath = { scriptRuntimeClasspath(classpathRegistry) },
        cache = compilationCache,
        evaluator = evaluator
    )
    private val diagnostics = GenerationDiagnostics(config, host.logger)
    private val generationStager = GenerationStager(
        backend,
        globalExecution,
        diagnostics
    )
    private val generationState = GenerationStateStore()
    private val generationRetirement = GenerationRetirementService(
        generationState,
        diagnostics,
        host.logger
    )
    private val generationEngine = GenerationLifecycleEngine(
        generationStager,
        globalExecution,
        diagnostics,
        host.logger,
        generationState,
        generationRetirement
    )
    private val generationCoordinator = ScriptGenerationCoordinator(generationEngine)
    private val scriptManager = ScriptManager(generationCoordinator)
    private val dataManager = DataManager(
        host,
        server,
        globalExecution,
        scriptManager,
        environment,
        workspace,
        reloadManager,
        compilationCache,
        generationRegistry,
        feedback
    )
    private val metrics = MetricsService(
        plugin = host.plugin,
        enabled = { config.value(Config.METRICS) }
    )
    private val command = MainCommand(dataManager, workspace::status, feedback::reply)

    fun start() {
        synchronized(lifecycleLock) {
            check(state == State.NEW) { "PluginRuntime can only be started once." }
            state = State.STARTING
        }

        try {
            globalExecution.start()
            server.registerCommand(command)
            dataManager.start()
            metrics.start()
            synchronized(lifecycleLock) {
                state = State.RUNNING
            }
        } catch (exception: Throwable) {
            runCatching(::stop)
                .exceptionOrNull()
                ?.let(exception::addSuppressed)
            throw exception
        }
    }

    fun stop() {
        val shouldStop = synchronized(lifecycleLock) {
            if (state == State.STOPPING || state == State.STOPPED) {
                false
            } else {
                state = State.STOPPING
                true
            }
        }
        if (!shouldStop) return

        val failures = mutableListOf<Throwable>()
        stop(failures, dataManager::stop)
        stop(failures, scriptManager::stop)
        stop(failures, metrics::stop)
        stop(failures, environment::clear)
        stop(failures, generationRegistry::clear)
        stop(failures, globalExecution::shutdown)

        synchronized(lifecycleLock) {
            state = State.STOPPED
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun stop(failures: MutableList<Throwable>, action: () -> Unit) {
        runCatching(action).exceptionOrNull()?.let(failures::add)
    }

    private enum class State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
