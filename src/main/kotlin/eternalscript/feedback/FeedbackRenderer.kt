package eternalscript.feedback

import eternalscript.api.script.feedback.ScriptFeedbackLevel
import eternalscript.api.script.feedback.ScriptFeedbackMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

internal class FeedbackRenderer(
    private val pluginName: String,
    private val catalog: () -> FeedbackCatalog
) {
    fun render(view: FeedbackView): List<Component> {
        val catalog = catalog()
        return buildList {
            add(prefix().append(render(view.title, catalog).color(levelColor(view.level))))
            view.lines.forEach { line ->
                add(linePrefix(line.kind).append(render(line.text, catalog).color(lineColor(line.kind))))
            }
        }
    }

    fun renderSystem(text: FeedbackText): Component =
        render(text, catalog())

    fun renderScript(
        level: ScriptFeedbackLevel,
        message: ScriptFeedbackMessage
    ): List<Component> = buildList {
        add(prefix().append(message.title.colorIfAbsent(levelColor(level.toInternal()))))
        message.details.forEach { detail ->
            add(linePrefix(FeedbackLineKind.DETAIL).append(detail.colorIfAbsent(NamedTextColor.GRAY)))
        }
        message.hint?.let { hint ->
            add(linePrefix(FeedbackLineKind.HINT).append(hint.colorIfAbsent(NamedTextColor.YELLOW)))
        }
    }

    private fun render(text: FeedbackText, catalog: FeedbackCatalog): Component {
        val template = catalog.template(text.key)
        return template.tokens.fold(Component.empty()) { result, token ->
            when (token) {
                is FeedbackTemplateToken.Literal -> result.append(Component.text(token.value))
                is FeedbackTemplateToken.Placeholder -> result.append(
                    renderValue(text.arguments.getValue(token.name), catalog)
                )
            }
        }
    }

    private fun renderValue(value: FeedbackValue, catalog: FeedbackCatalog): Component = when (value) {
        is FeedbackValue.Literal -> Component.text(value.value)
        is FeedbackValue.Rich -> value.value
        is FeedbackValue.Nested -> render(value.value, catalog)
    }

    private fun prefix(): Component = Component.text("[", NamedTextColor.DARK_GRAY)
        .append(Component.text(pluginName, NamedTextColor.GOLD, TextDecoration.BOLD))
        .append(Component.text("] ", NamedTextColor.DARK_GRAY))

    private fun linePrefix(kind: FeedbackLineKind): Component = when (kind) {
        FeedbackLineKind.DETAIL -> Component.text("  • ", NamedTextColor.DARK_GRAY)
        FeedbackLineKind.HINT -> Component.text("  → ", NamedTextColor.GOLD)
        FeedbackLineKind.ERROR -> Component.text("  ! ", NamedTextColor.RED)
    }

    private fun lineColor(kind: FeedbackLineKind): NamedTextColor = when (kind) {
        FeedbackLineKind.DETAIL -> NamedTextColor.GRAY
        FeedbackLineKind.HINT -> NamedTextColor.YELLOW
        FeedbackLineKind.ERROR -> NamedTextColor.RED
    }

    private fun levelColor(level: FeedbackLevel): NamedTextColor = when (level) {
        FeedbackLevel.INFO -> NamedTextColor.AQUA
        FeedbackLevel.SUCCESS -> NamedTextColor.GREEN
        FeedbackLevel.WARNING -> NamedTextColor.YELLOW
        FeedbackLevel.ERROR -> NamedTextColor.RED
    }

    private fun ScriptFeedbackLevel.toInternal(): FeedbackLevel = when (this) {
        ScriptFeedbackLevel.INFO -> FeedbackLevel.INFO
        ScriptFeedbackLevel.SUCCESS -> FeedbackLevel.SUCCESS
        ScriptFeedbackLevel.WARNING -> FeedbackLevel.WARNING
        ScriptFeedbackLevel.ERROR -> FeedbackLevel.ERROR
    }
}

private fun Component.colorIfAbsent(color: NamedTextColor): Component =
    if (style().color() == null) color(color) else this
