package eternalscript.messaging

import eternalscript.api.script.notification.ScriptNotification
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

internal class MessageRenderer(
    private val pluginName: String,
    private val catalog: () -> MessageCatalog
) {
    fun render(view: MessageView): List<Component> {
        val catalog = catalog()
        return buildList {
            add(prefix().append(render(view.title, catalog).color(levelColor(view.level))))
            view.lines.forEach { line ->
                add(linePrefix(line.kind).append(render(line.text, catalog).color(lineColor(line.kind))))
            }
        }
    }

    fun renderSystem(text: MessageText): Component =
        render(text, catalog())

    fun renderNotification(
        level: NotificationLevel,
        message: ScriptNotification
    ): List<Component> = buildList {
        add(prefix().append(message.title.colorIfAbsent(levelColor(level.toInternal()))))
        message.details.forEach { detail ->
            add(linePrefix(MessageLineKind.DETAIL).append(detail.colorIfAbsent(NamedTextColor.GRAY)))
        }
        message.hint?.let { hint ->
            add(linePrefix(MessageLineKind.HINT).append(hint.colorIfAbsent(NamedTextColor.YELLOW)))
        }
    }

    private fun render(text: MessageText, catalog: MessageCatalog): Component {
        val template = catalog.template(text.key)
        return template.tokens.fold(Component.empty()) { result, token ->
            when (token) {
                is MessageTemplateToken.Literal -> result.append(Component.text(token.value))
                is MessageTemplateToken.Placeholder -> result.append(
                    renderValue(text.arguments.getValue(token.name), catalog)
                )
            }
        }
    }

    private fun renderValue(value: MessageValue, catalog: MessageCatalog): Component = when (value) {
        is MessageValue.Literal -> Component.text(value.value)
        is MessageValue.Rich -> value.value
        is MessageValue.Nested -> render(value.value, catalog)
    }

    private fun prefix(): Component = Component.text("[", NamedTextColor.DARK_GRAY)
        .append(Component.text(pluginName, NamedTextColor.GOLD, TextDecoration.BOLD))
        .append(Component.text("] ", NamedTextColor.DARK_GRAY))

    private fun linePrefix(kind: MessageLineKind): Component = when (kind) {
        MessageLineKind.DETAIL -> Component.text("  • ", NamedTextColor.DARK_GRAY)
        MessageLineKind.HINT -> Component.text("  → ", NamedTextColor.GOLD)
        MessageLineKind.ERROR -> Component.text("  ! ", NamedTextColor.RED)
    }

    private fun lineColor(kind: MessageLineKind): NamedTextColor = when (kind) {
        MessageLineKind.DETAIL -> NamedTextColor.GRAY
        MessageLineKind.HINT -> NamedTextColor.YELLOW
        MessageLineKind.ERROR -> NamedTextColor.RED
    }

    private fun levelColor(level: MessageLevel): NamedTextColor = when (level) {
        MessageLevel.INFO -> NamedTextColor.AQUA
        MessageLevel.SUCCESS -> NamedTextColor.GREEN
        MessageLevel.WARNING -> NamedTextColor.YELLOW
        MessageLevel.ERROR -> NamedTextColor.RED
    }

    private fun NotificationLevel.toInternal(): MessageLevel = when (this) {
        NotificationLevel.INFO -> MessageLevel.INFO
        NotificationLevel.SUCCESS -> MessageLevel.SUCCESS
        NotificationLevel.WARN -> MessageLevel.WARNING
        NotificationLevel.ERROR -> MessageLevel.ERROR
    }
}

private fun Component.colorIfAbsent(color: NamedTextColor): Component =
    if (style().color() == null) color(color) else this
