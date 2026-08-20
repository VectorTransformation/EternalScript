package eternalscript.api.script.feedback

import net.kyori.adventure.text.Component

public enum class ScriptFeedbackLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

public data class ScriptFeedbackMessage(
    public val title: Component,
    public val details: List<Component> = emptyList(),
    public val hint: Component? = null
) {
    public constructor(
        title: String,
        details: List<String> = emptyList(),
        hint: String? = null
    ) : this(
        Component.text(title),
        details.map(Component::text),
        hint?.let(Component::text)
    )
}
