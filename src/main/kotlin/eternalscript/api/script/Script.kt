package eternalscript.api.script

import eternalscript.api.script.command.ScriptCommandBuilder
import eternalscript.api.script.logging.ScriptLogger
import eternalscript.api.script.notification.ScriptNotifier
import eternalscript.api.script.storage.ScriptTask
import eternalscript.api.script.storage.Storage
import eternalscript.api.script.storage.StorageKey
import eternalscript.api.script.storage.StorageKeys
import eternalscript.logging.ScriptLoggingRuntime
import eternalscript.messaging.ScriptNotificationBridge
import eternalscript.scripting.runtime.declaration.ScriptDeclarationSnapshot
import eternalscript.scripting.runtime.declaration.ScriptDeclarations
import eternalscript.scripting.runtime.declaration.ScriptEventDefinition
import eternalscript.storage.ScriptStorageRuntime
import eternalscript.storage.ScriptTaskOwner
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import net.kyori.adventure.audience.Audience
import kotlin.reflect.KClass
public abstract class Script {
    private val declarations: ScriptDeclarations = ScriptDeclarations()
    private val storageTasks: ScriptTaskOwner = ScriptTaskOwner()
    private val scriptLogger = ScriptLoggingRuntime.logger()

    /** Writes operational messages to the Paper server log with this script's logical path. */
    public val log: ScriptLogger
        get() = scriptLogger

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

    public fun storage(namespace: String): Storage = ScriptStorageRuntime.storage(namespace)

    public fun storageTask(block: suspend () -> Unit): ScriptTask =
        ScriptStorageRuntime.launch(storageTasks, block)

    public fun stringKey(name: String): StorageKey<String?> = StorageKeys.string(name)

    public fun stringKey(name: String, default: String): StorageKey<String> =
        StorageKeys.string(name, default)

    public fun booleanKey(name: String, default: Boolean = false): StorageKey<Boolean> =
        StorageKeys.boolean(name, default)

    public fun intKey(name: String, default: Int = 0): StorageKey<Int> = StorageKeys.int(name, default)

    public fun longKey(name: String, default: Long = 0L): StorageKey<Long> = StorageKeys.long(name, default)

    public fun doubleKey(name: String, default: Double = 0.0): StorageKey<Double> =
        StorageKeys.double(name, default)

    public fun uuidKey(name: String): StorageKey<UUID?> = StorageKeys.uuid(name)

    public fun uuidKey(name: String, default: UUID): StorageKey<UUID> = StorageKeys.uuid(name, default)

    public fun bytesKey(name: String): StorageKey<ByteArray?> = StorageKeys.bytes(name)

    public fun bytesKey(name: String, default: ByteArray): StorageKey<ByteArray> =
        StorageKeys.bytes(name, default)

    public fun stringListKey(
        name: String,
        default: List<String> = emptyList()
    ): StorageKey<List<String>> = StorageKeys.stringList(name, default)

    public fun stringSetKey(
        name: String,
        default: Set<String> = emptySet()
    ): StorageKey<Set<String>> = StorageKeys.stringSet(name, default)

    public fun jsonKey(name: String): StorageKey<JsonElement?> = StorageKeys.json(name)

    public fun jsonKey(name: String, default: JsonElement): StorageKey<JsonElement> =
        StorageKeys.json(name, default)

    /** Selects an Adventure audience for chat notifications, defaulting to the Paper console. */
    public fun notify(audience: Audience = Bukkit.getConsoleSender()): ScriptNotifier =
        ScriptNotificationBridge.notifier(audience)

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
        storageTasks.close()
        declarations.dispose()
    }

    internal fun attachRuntimeSource(path: String) {
        storageTasks.attachSource(path)
        scriptLogger.attachSource(path)
    }
}
