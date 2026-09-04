package eternalscript.messaging

import eternalscript.api.script.notification.ScriptNotification
import eternalscript.config.ConfigService
import eternalscript.config.PluginPaths
import eternalscript.logging.EternalLogLevel
import eternalscript.logging.UnifiedLoggingService
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.audience.Audience
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin

internal class UnifiedMessagingService(
    private val plugin: JavaPlugin,
    private val paths: PluginPaths,
    private val config: ConfigService,
    private val logging: UnifiedLoggingService
) {
    private val activeCatalog = AtomicReference<MessageCatalog?>()
    private val renderer = MessageRenderer(
        plugin.name,
        { checkNotNull(activeCatalog.get()) { "Message catalogs have not been loaded" } }
    )

    fun isReady(): Boolean = activeCatalog.get() != null

    fun reload(): List<MessageCatalogIssue> {
        val bundled = BUNDLED_LOCALES.associateWith { locale ->
            plugin.getResource("lang/$locale.json")?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                reader.readText()
            } ?: error("Bundled message catalog is missing: lang/$locale.json")
        }
        val loaded = MessageCatalogLoader.load(bundled, paths.languagesDirectory)
        val configReport = config.reload(loaded.catalogs.locales())
        activeCatalog.set(loaded.catalogs.catalog(configReport.config.language))
        val issues = loaded.issues + configReport.issues.map { issue ->
            MessageCatalogIssue(issue.file, issue.reason)
        }
        issues.forEach { issue ->
            system(
                systemMessage(
                    MessageLevel.WARNING,
                    MessageKey.SYSTEM_CATALOG_ISSUE,
                    "file" to issue.file,
                    "reason" to issue.reason
                )
            )
        }
        return issues
    }

    fun send(sender: CommandSender, view: MessageView) {
        renderer.render(view).forEach(sender::sendMessage)
    }

    fun sendNotification(
        audience: Audience,
        level: NotificationLevel,
        message: ScriptNotification
    ) {
        renderer.renderNotification(level, message).forEach(audience::sendMessage)
    }

    fun system(message: SystemMessage) {
        val rendered = renderer.renderSystem(message.text)
        logging.system(message.level.toLogLevel(), rendered, message.cause)
    }

    private companion object {
        val BUNDLED_LOCALES: List<String> = listOf("en_US", "ko_KR", "ja_JP", "zh_CN")
    }
}

internal fun MessageLevel.toLogLevel(): EternalLogLevel = when (this) {
    MessageLevel.INFO,
    MessageLevel.SUCCESS -> EternalLogLevel.INFO
    MessageLevel.WARNING -> EternalLogLevel.WARN
    MessageLevel.ERROR -> EternalLogLevel.ERROR
}
