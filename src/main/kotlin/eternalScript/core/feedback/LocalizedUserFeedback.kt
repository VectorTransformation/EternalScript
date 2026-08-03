package eternalScript.core.feedback

import eternalScript.core.data.Config
import eternalScript.core.manager.ConfigManager
import eternalScript.core.the.Root
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.logging.Level

internal sealed interface UserFeedbackTarget {
    data class Reply(val sender: CommandSender) : UserFeedbackTarget
    data object ServerLog : UserFeedbackTarget
    data object Silent : UserFeedbackTarget
}

internal object UserFeedbackChannels {
    val serverLog: UserFeedback = LocalizedUserFeedback(UserFeedbackTarget.ServerLog)
    val silent: UserFeedback = LocalizedUserFeedback(UserFeedbackTarget.Silent)

    fun reply(sender: CommandSender): UserFeedback {
        val language = ConfigManager.value<String>(Config.LANG)
        return LocalizedUserFeedback(
            target = UserFeedbackTarget.Reply(sender),
            language = { language }
        )
    }
}

internal class UserFeedbackTextRenderer(
    private val translate: (key: String, language: String) -> String
) {
    fun render(message: UserFeedbackMessage, language: String): String {
        val template = translate(message.key, language)
        val arguments = message.arguments.map { argument ->
            when (argument) {
                is UserFeedbackArgument.Text -> literal(argument.value)
                is UserFeedbackArgument.Quoted -> "\"${literal(argument.value)}\""
                is UserFeedbackArgument.Translation -> translate(argument.key, language)
            }
        }
        return template.format(*arguments.toTypedArray())
    }

    private fun literal(value: String): String = value
        .replace("\r", "\\r")
        .replace("\n", "\\n")
}

private class LocalizedUserFeedback(
    private val target: UserFeedbackTarget,
    private val language: () -> String = { ConfigManager.value(Config.LANG) },
    private val delivery: LocalizedUserFeedbackDelivery = LocalizedUserFeedbackDelivery()
) : UserFeedback {
    override fun emit(event: UserFeedbackEvent) {
        if (target == UserFeedbackTarget.Silent) return
        runCatching {
            delivery.deliver(target, event, language())
        }.onFailure { exception ->
            Root.INSTANCE.logger.log(
                Level.SEVERE,
                "Failed to render EternalScript user feedback event " +
                    event.javaClass.simpleName,
                exception
            )
        }
    }

}

/** Testable Bukkit/log routing adapter for already structured feedback. */
internal class LocalizedUserFeedbackDelivery(
    private val presenter: UserFeedbackPresenter = UserFeedbackPresenter,
    private val renderer: UserFeedbackTextRenderer = UserFeedbackTextRenderer(LocaleCatalog::translate),
    private val replySink: (CommandSender, String) -> Unit = { sender, message ->
        sender.sendMessage(Component.text("[EternalScript] $message"))
    },
    private val logSink: (UserFeedbackMessage, String) -> Unit = ::defaultLog
) {
    fun deliver(
        target: UserFeedbackTarget,
        event: UserFeedbackEvent,
        language: String
    ) {
        when (target) {
            is UserFeedbackTarget.Reply -> reply(target.sender, event, language)
            UserFeedbackTarget.ServerLog -> log(presenter.present(event), language)
            UserFeedbackTarget.Silent -> Unit
        }
    }

    private fun reply(sender: CommandSender, event: UserFeedbackEvent, language: String) {
        val limit = if (sender is Player) MAX_PLAYER_DETAILS else null
        val concise = presenter.present(event, limit)
        concise.forEach { message ->
            replySink(sender, renderer.render(message, language))
        }

        if (sender is Player) {
            val full = presenter.present(event)
            if (full.countDetails() > concise.countDetails()) {
                log(full.filter { it.stage == UserFeedbackStage.DETAIL }, language)
            }
        }
    }

    private fun log(messages: List<UserFeedbackMessage>, language: String) {
        messages.forEach { message ->
            logSink(message, renderer.render(message, language))
        }
    }

    private fun List<UserFeedbackMessage>.countDetails() =
        count { it.stage == UserFeedbackStage.DETAIL }

    private companion object {
        fun defaultLog(message: UserFeedbackMessage, text: String) {
            when {
                message.internalFailure -> Root.INSTANCE.logger.severe(text)
                message.severity == UserFeedbackSeverity.ERROR -> Root.INSTANCE.logger.warning(text)
                message.severity == UserFeedbackSeverity.WARNING -> Root.INSTANCE.logger.warning(text)
                else -> Root.INSTANCE.logger.info(text)
            }
        }
    }
}

private const val MAX_PLAYER_DETAILS = 5
