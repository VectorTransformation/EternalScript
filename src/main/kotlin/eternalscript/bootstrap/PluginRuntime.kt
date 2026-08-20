package eternalscript.bootstrap

import eternalscript.api.EternalScriptApi
import eternalscript.api.ScriptOperationStatus
import eternalscript.command.MainCommand
import eternalscript.config.ConfigService
import eternalscript.config.PluginPaths
import eternalscript.feedback.FeedbackKey
import eternalscript.feedback.FeedbackLevel
import eternalscript.feedback.FeedbackService
import eternalscript.feedback.FeedbackLine
import eternalscript.feedback.FeedbackLineKind
import eternalscript.feedback.FeedbackView
import eternalscript.feedback.ScriptFeedbackBridge
import eternalscript.feedback.feedbackText
import eternalscript.feedback.systemFeedback
import eternalscript.metrics.MetricsService
import eternalscript.ide.EternalScriptIdeEnvironmentPublisher
import eternalscript.scripting.runtime.ScriptEngine
import eternalscript.scripting.source.ScriptSourceRepository
import org.bukkit.command.CommandSender
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

internal class PluginRuntime(
    private val plugin: JavaPlugin
) : AutoCloseable {
    private val paths = PluginPaths(plugin)
    private val config = ConfigService(paths.configFile)
    private val feedback = FeedbackService(plugin, paths, config)
    private val sources = ScriptSourceRepository(plugin, paths)
    private val ideEnvironment = EternalScriptIdeEnvironmentPublisher(
        paths.dataDirectory,
        plugin.pluginMeta.version,
        feedback::system
    )
    private val engine = ScriptEngine(plugin, paths, sources, feedback::system, ideEnvironment)
    private val api = EternalScriptApiService(engine)
    private val metrics = MetricsService(plugin) { config.current.metricsEnabled }
    private val command = MainCommand(plugin, api, sources, feedback, ::reloadConfiguration)
    private var serviceRegistered = false
    private var scriptFeedbackRegistration: AutoCloseable? = null
    private var closed = false

    fun enable() {
        try {
            paths.ensureBaseDirectories()
            sources.installBundledResources()
            feedback.reload()
            prepareIdeEnvironment()
            scriptFeedbackRegistration = ScriptFeedbackBridge.install(feedback::sendScript)
            registerApi()
            command.register()

            val startup = engine.startup()
            if (startup.status == ScriptOperationStatus.SUCCESS) {
                feedback.system(
                    systemFeedback(
                        FeedbackLevel.SUCCESS,
                        FeedbackKey.SYSTEM_STARTUP_SUCCESS,
                        "count" to startup.affectedPaths.size
                    )
                )
            } else {
                feedback.system(
                    systemFeedback(
                        FeedbackLevel.WARNING,
                        FeedbackKey.SYSTEM_STARTUP_DEGRADED
                    )
                )
            }
            metrics.start()
        } catch (error: Throwable) {
            runCatching(::close).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    fun reloadConfiguration(sender: CommandSender) {
        val issues = feedback.reload()
        metrics.refresh()
        val title = if (issues.isEmpty()) {
            feedbackText(FeedbackKey.COMMAND_CONFIG_SUCCESS)
        } else {
            feedbackText(FeedbackKey.COMMAND_CONFIG_WITH_ISSUES, "count" to issues.size)
        }
        feedback.send(
            sender,
            FeedbackView(
                if (issues.isEmpty()) FeedbackLevel.SUCCESS else FeedbackLevel.WARNING,
                title,
                buildList {
                    issues.take(MAX_CONFIG_ISSUES_IN_COMMAND).forEach { issue ->
                        add(
                            FeedbackLine(
                                feedbackText(
                                    FeedbackKey.COMMAND_CONFIG_ISSUE,
                                    "file" to issue.file,
                                    "reason" to issue.reason
                                ),
                                FeedbackLineKind.ERROR
                            )
                        )
                    }
                    val remaining = issues.size - MAX_CONFIG_ISSUES_IN_COMMAND
                    if (remaining > 0) {
                        add(
                            FeedbackLine(
                                feedbackText(FeedbackKey.COMMAND_CONFIG_MORE_ISSUES, "count" to remaining),
                                FeedbackLineKind.HINT
                            )
                        )
                    }
                }
            )
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        unregisterApi()
        try {
            engine.shutdown()
        } finally {
            try {
                metrics.close()
            } finally {
                scriptFeedbackRegistration?.close()
                scriptFeedbackRegistration = null
            }
        }
        if (feedback.isReady()) {
            feedback.system(
                systemFeedback(FeedbackLevel.SUCCESS, FeedbackKey.SYSTEM_SHUTDOWN_SUCCESS)
            )
        }
    }

    private fun registerApi() {
        plugin.server.servicesManager.register(
            EternalScriptApi::class.java,
            api,
            plugin,
            ServicePriority.Normal
        )
        serviceRegistered = true
    }

    private fun unregisterApi() {
        if (!serviceRegistered) return
        plugin.server.servicesManager.unregister(EternalScriptApi::class.java, api)
        serviceRegistered = false
    }

    private fun prepareIdeEnvironment() {
        val report = runCatching(ideEnvironment::prepare).getOrElse { error ->
            feedback.system(
                systemFeedback(
                    FeedbackLevel.WARNING,
                    FeedbackKey.SYSTEM_IDE_LEGACY_CLEANUP_FAILED,
                    "path" to ".eternalscript/ide",
                    "error" to (error.message ?: error.javaClass.name),
                    cause = error
                )
            )
            return
        }
        report.preservedPaths.forEach { path ->
            feedback.system(
                systemFeedback(
                    FeedbackLevel.WARNING,
                    FeedbackKey.SYSTEM_IDE_LEGACY_PRESERVED,
                    "path" to path
                )
            )
        }
        report.failures.forEach { (path, reason) ->
            feedback.system(
                systemFeedback(
                    FeedbackLevel.WARNING,
                    FeedbackKey.SYSTEM_IDE_LEGACY_CLEANUP_FAILED,
                    "path" to path,
                    "error" to reason
                )
            )
        }
    }

    private companion object {
        const val MAX_CONFIG_ISSUES_IN_COMMAND: Int = 5
    }
}
