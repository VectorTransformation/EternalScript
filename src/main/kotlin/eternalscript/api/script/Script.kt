package eternalscript.api.script

import eternalscript.api.script.command.ScriptCommandBuilder
import eternalscript.api.script.feedback.ScriptFeedbackLevel
import eternalscript.api.script.feedback.ScriptFeedbackMessage
import eternalscript.feedback.ScriptFeedbackBridge
import eternalscript.scripting.runtime.declaration.ScriptDeclarationSnapshot
import eternalscript.scripting.runtime.declaration.ScriptDeclarations
import eternalscript.scripting.runtime.declaration.ScriptEventDefinition
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import net.kyori.adventure.audience.Audience
import kotlin.reflect.KClass
public abstract class Script {
    private val declarations: ScriptDeclarations = ScriptDeclarations()

    public fun onLoad(block: () -> Unit) {
        declarations.addLoad(block)
    }

    public fun onUnload(block: () -> Unit) {
        declarations.addUnload(block)
    }

    /**
     * Registers cleanup that runs when this evaluated script instance is discarded.
     *
     * Unlike [onUnload], disposal also runs when the candidate never reaches [onLoad],
     * such as after an evaluation or activation failure. Disposal callbacks run once in
     * reverse registration order, and one failure does not prevent later cleanup.
     */
    public fun onDispose(block: () -> Unit) {
        declarations.addDispose(block)
    }

    /** Owns an [AutoCloseable] for the lifetime of this evaluated script instance. */
    public fun <T : AutoCloseable> own(resource: T): T = own(resource) { owned -> owned.close() }

    /**
     * Owns [resource] for the lifetime of this evaluated script instance using [disposer].
     * If the script is already disposing, [disposer] runs immediately before registration fails.
     */
    public fun <T> own(resource: T, disposer: (T) -> Unit): T {
        try {
            declarations.addDispose { disposer(resource) }
        } catch (registrationFailure: Throwable) {
            try {
                disposer(resource)
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== registrationFailure) {
                    registrationFailure.addSuppressed(cleanupFailure)
                }
            }
            throw registrationFailure
        }
        return resource
    }

    public inline fun <reified T : Event> on(
        priority: EventPriority = EventPriority.NORMAL,
        noinline block: (T) -> Unit
    ) {
        declareEvent(T::class, priority, block)
    }

    public fun command(name: String, block: ScriptCommandBuilder.() -> Unit) {
        declarations.addCommand(ScriptCommandBuilder(name).apply(block).definition())
    }

    /**
     * Sends a consistently styled EternalScript message to a player, command sender, or other Adventure audience.
     * Plain string values are always treated as literal text and never parsed as formatting markup.
     */
    public fun feedback(
        audience: Audience,
        title: String,
        level: ScriptFeedbackLevel = ScriptFeedbackLevel.INFO
    ) {
        feedback(audience, ScriptFeedbackMessage(title), level)
    }

    /**
     * Sends a structured EternalScript message. Components supplied by the script retain their Adventure styling.
     */
    public fun feedback(
        audience: Audience,
        message: ScriptFeedbackMessage,
        level: ScriptFeedbackLevel = ScriptFeedbackLevel.INFO
    ) {
        ScriptFeedbackBridge.send(audience, level, message)
    }

    @PublishedApi
    internal fun <T : Event> declareEvent(
        eventType: KClass<T>,
        priority: EventPriority,
        block: (T) -> Unit
    ) {
        declarations.addEvent(
            ScriptEventDefinition(eventType, priority) { event ->
                block(eventType.java.cast(event))
            }
        )
    }

    internal fun freezeDeclarations(): ScriptDeclarationSnapshot = declarations.freeze()

    internal fun disposeDeclarations() {
        declarations.dispose()
    }
}
