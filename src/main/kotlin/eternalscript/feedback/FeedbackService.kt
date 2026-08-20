package eternalscript.feedback

import eternalscript.api.script.feedback.ScriptFeedbackLevel
import eternalscript.api.script.feedback.ScriptFeedbackMessage
import eternalscript.config.ConfigService
import eternalscript.config.PluginPaths
import net.kyori.adventure.audience.Audience
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicReference

internal class FeedbackService(
    private val plugin: JavaPlugin,
    private val paths: PluginPaths,
    private val config: ConfigService
) {
    private val activeCatalog = AtomicReference<FeedbackCatalog?>()
    private val renderer = FeedbackRenderer(
        plugin.name,
        { checkNotNull(activeCatalog.get()) { "Feedback catalogs have not been loaded" } }
    )

    fun isReady(): Boolean = activeCatalog.get() != null

    fun reload(): List<FeedbackCatalogIssue> {
        val bundled = BUNDLED_LOCALES.associateWith { locale ->
            plugin.getResource("lang/$locale.json")?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                reader.readText()
            } ?: error("Bundled feedback catalog is missing: lang/$locale.json")
        }
        val loaded = FeedbackCatalogLoader.load(bundled, paths.languagesDirectory)
        val configReport = config.reload(loaded.catalogs.locales())
        activeCatalog.set(loaded.catalogs.catalog(configReport.config.language))
        val issues = loaded.issues + configReport.issues.map { issue ->
            FeedbackCatalogIssue(issue.file, issue.reason)
        }
        issues.forEach { issue ->
            system(
                systemFeedback(
                    FeedbackLevel.WARNING,
                    FeedbackKey.SYSTEM_CATALOG_ISSUE,
                    "file" to issue.file,
                    "reason" to issue.reason
                )
            )
        }
        return issues
    }

    fun send(sender: CommandSender, view: FeedbackView) {
        renderer.render(view).forEach(sender::sendMessage)
    }

    fun sendScript(
        audience: Audience,
        level: ScriptFeedbackLevel,
        message: ScriptFeedbackMessage
    ) {
        renderer.renderScript(level, message).forEach(audience::sendMessage)
    }

    fun system(feedback: SystemFeedback) {
        val message = renderer.renderSystem(feedback.text)
        when (feedback.level) {
            FeedbackLevel.INFO,
            FeedbackLevel.SUCCESS -> plugin.componentLogger.info(message)
            FeedbackLevel.WARNING -> {
                val cause = feedback.cause
                if (cause == null) {
                    plugin.componentLogger.warn(message)
                } else {
                    plugin.componentLogger.warn(message, cause)
                }
            }
            FeedbackLevel.ERROR -> {
                val cause = feedback.cause
                if (cause == null) {
                    plugin.componentLogger.error(message)
                } else {
                    plugin.componentLogger.error(message, cause)
                }
            }
        }
    }

    private companion object {
        val BUNDLED_LOCALES: List<String> = listOf("en_US", "ko_KR", "ja_JP", "zh_CN")
    }
}
