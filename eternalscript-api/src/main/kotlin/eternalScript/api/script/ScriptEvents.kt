package eternalScript.api.script

import org.bukkit.event.Event
import org.bukkit.event.EventPriority

/** Event definitions for one [EternalScript] activation. */
@EternalScriptDsl
@OptIn(InternalEternalScriptRuntimeApi::class)
class ScriptEvents internal constructor(
    @PublishedApi internal val script: EternalScript
) {
    inline fun <reified T : Event> on(
        priority: EventPriority = EventPriority.NORMAL,
        noinline block: (T) -> Unit
    ) {
        script.runtimeForDsl().event(T::class, priority, block)
    }
}
