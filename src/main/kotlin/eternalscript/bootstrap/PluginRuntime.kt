package eternalscript.bootstrap

import eternalscript.api.EternalScriptApi
import eternalscript.api.ScriptOperationStatus
import eternalscript.command.MainCommand
import eternalscript.config.ConfigService
import eternalscript.config.PluginPaths
import eternalscript.messaging.MessageKey
import eternalscript.messaging.MessageLevel
import eternalscript.messaging.MessageLine
import eternalscript.messaging.MessageLineKind
import eternalscript.messaging.MessageView
import eternalscript.messaging.messageText
import eternalscript.messaging.systemMessage
import eternalscript.metrics.MetricsService
import eternalscript.logging.ScriptLoggingRuntime
import eternalscript.logging.UnifiedLoggingService
import eternalscript.messaging.ScriptNotificationBridge
import eternalscript.messaging.UnifiedMessagingService
import eternalscript.ide.EternalScriptIdeEnvironmentPublisher
import eternalscript.scripting.runtime.ScriptEngine
import eternalscript.scripting.source.ScriptSourceRepository
import eternalscript.storage.SQLiteStorageService
import eternalscript.storage.ScriptStorageRuntime
import org.bukkit.command.CommandSender
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

internal class PluginRuntime(
    private val plugin: JavaPlugin
) : AutoCloseable {
    private val paths = PluginPaths(plugin)
    private val config = ConfigService(paths.configFile)
    private val logging = UnifiedLoggingService(
        plugin,
        level = { config.current.loggingLevel },
        slowStorageMillis = { config.current.slowStorageMillis }
    )
    private val messaging = UnifiedMessagingService(plugin, paths, config, logging)
    private val sources = ScriptSourceRepository(plugin, paths)
    private val ideEnvironment = EternalScriptIdeEnvironmentPublisher(
        paths.dataDirectory,
        messaging::system
    )
    private val storage = SQLiteStorageService(paths.storageDatabaseFile.toPath())
    private val engine = ScriptEngine(
        plugin,
        paths,
        sources,
        messaging::system,
        ideEnvironment,
        cacheEnabled = { config.current.cacheEnabled }
    )
    private val api = EternalScriptApiService(engine)
    private val metrics = MetricsService(plugin) { config.current.metricsEnabled }
    private val command = MainCommand(plugin, api, sources, messaging, ::reloadConfiguration)
    private var serviceRegistered = false
    private var scriptNotificationRegistration: AutoCloseable? = null
    private var scriptLoggingRegistration: AutoCloseable? = null
    private var scriptStorageRegistration: AutoCloseable? = null
    private var closed = false

    fun enable() {
        try {
            paths.ensureBaseDirectories()
            scriptLoggingRegistration = ScriptLoggingRuntime.install(logging)
            storage.open()
            scriptStorageRegistration = ScriptStorageRuntime.install(plugin, storage, logging)
            sources.installBundledResources()
            messaging.reload()
            scriptNotificationRegistration = ScriptNotificationBridge.install(messaging::sendNotification)
            registerApi()
            command.register()

            val startup = engine.startup()
            if (startup.status == ScriptOperationStatus.SUCCESS) {
                messaging.system(
                    systemMessage(
                        MessageLevel.SUCCESS,
                        MessageKey.SYSTEM_STARTUP_SUCCESS,
                        "count" to startup.affectedPaths.size
                    )
                )
            } else {
                messaging.system(
                    systemMessage(
                        MessageLevel.WARNING,
                        MessageKey.SYSTEM_STARTUP_DEGRADED
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
        val issues = messaging.reload()
        metrics.refresh()
        val title = if (issues.isEmpty()) {
            messageText(MessageKey.COMMAND_CONFIG_SUCCESS)
        } else {
            messageText(MessageKey.COMMAND_CONFIG_WITH_ISSUES, "count" to issues.size)
        }
        messaging.send(
            sender,
            MessageView(
                if (issues.isEmpty()) MessageLevel.SUCCESS else MessageLevel.WARNING,
                title,
                buildList {
                    issues.take(MAX_CONFIG_ISSUES_IN_COMMAND).forEach { issue ->
                        add(
                            MessageLine(
                                messageText(
                                    MessageKey.COMMAND_CONFIG_ISSUE,
                                    "file" to issue.file,
                                    "reason" to issue.reason
                                ),
                                MessageLineKind.ERROR
                            )
                        )
                    }
                    val remaining = issues.size - MAX_CONFIG_ISSUES_IN_COMMAND
                    if (remaining > 0) {
                        add(
                            MessageLine(
                                messageText(MessageKey.COMMAND_CONFIG_MORE_ISSUES, "count" to remaining),
                                MessageLineKind.HINT
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
        val failures = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let(failures::add)
        }
        attempt(engine::shutdown)
        attempt {
            scriptStorageRegistration?.close()
            scriptStorageRegistration = null
        }
        attempt(storage::close)
        attempt(metrics::close)
        attempt {
            scriptNotificationRegistration?.close()
            scriptNotificationRegistration = null
        }
        if (messaging.isReady()) attempt {
            messaging.system(systemMessage(MessageLevel.SUCCESS, MessageKey.SYSTEM_SHUTDOWN_SUCCESS))
        }
        attempt {
            scriptLoggingRegistration?.close()
            scriptLoggingRegistration = null
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
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

    private companion object {
        const val MAX_CONFIG_ISSUES_IN_COMMAND: Int = 5
    }
}
