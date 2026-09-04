package eternalscript.api.script.notification

import net.kyori.adventure.text.Component

/** A structured chat notification whose existing Adventure styles are preserved. */
public data class ScriptNotification(
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

/** Sends chat notifications to the Audience selected by [eternalscript.api.script.Script.notify]. */
public interface ScriptNotifier {
    public fun info(message: String)
    public fun info(message: Component)
    public fun info(message: ScriptNotification)

    public fun success(message: String)
    public fun success(message: Component)
    public fun success(message: ScriptNotification)

    public fun warn(message: String)
    public fun warn(message: Component)
    public fun warn(message: ScriptNotification)

    public fun error(message: String)
    public fun error(message: Component)
    public fun error(message: ScriptNotification)
}
