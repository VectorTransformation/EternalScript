package eternalScript.api.script

import eternalScript.api.script.command.ScriptCommands
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext

/**
 * Entry point for one independently managed feature in a script project.
 *
 * EternalScript discovers every concrete subclass with an accessible
 * no-argument constructor, activates them in class-name order, and disables
 * them in reverse order when the project is replaced or unloaded.
 */
@OptIn(InternalEternalScriptRuntimeApi::class)
abstract class EternalScript protected constructor() {
    private val runtimeLock = Any()
    @Volatile
    private var runtimeBridge: EternalScriptRuntimeBridge? = null
    @Volatile
    private var runtimeState = RuntimeState.CONSTRUCTING

    /** The EternalScript plugin instance for Paper and Folia scheduler APIs. */
    val plugin: Plugin
        get() = runtime().plugin

    protected open fun onEnable() {}

    protected open fun onDisable() {}

    fun events(block: ScriptEvents.() -> Unit) {
        runtime()
        ScriptEvents(this).block()
    }

    fun commands(block: ScriptCommands.() -> Unit) {
        runtime()
        ScriptCommands(this).block()
    }

    fun <T : Job> track(job: T): T = runtime().track(job)

    fun <T : BukkitTask> track(task: T): T = runtime().track(task)

    fun <T : ScheduledTask> track(task: T): T = runtime().track(task)

    fun task(block: () -> Unit): Runnable = runtime().task(block)

    fun <T> task(block: (T) -> Unit): Consumer<T> = runtime().task(block)

    fun launch(
        context: CoroutineContext = Dispatchers.Default,
        block: suspend CoroutineScope.() -> Unit
    ): Job = runtime().launch(context, block)

    fun <T> async(
        context: CoroutineContext = Dispatchers.Default,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = runtime().async(context, block)

    @PublishedApi
    internal fun runtimeForDsl(): EternalScriptRuntimeBridge = runtime()

    internal fun attachRuntime(bridge: EternalScriptRuntimeBridge) {
        synchronized(runtimeLock) {
            check(runtimeState == RuntimeState.CONSTRUCTING && runtimeBridge == null) {
                "An EternalScript runtime can only be attached once after construction."
            }
            runtimeBridge = bridge
            runtimeState = RuntimeState.ATTACHED
        }
    }

    internal fun detachRuntime(bridge: EternalScriptRuntimeBridge) {
        synchronized(runtimeLock) {
            check(runtimeState == RuntimeState.ATTACHED && runtimeBridge === bridge) {
                "The attached EternalScript runtime does not match the disposing runtime."
            }
            runtimeBridge = null
            runtimeState = RuntimeState.DISPOSED
        }
    }

    internal fun invokeEnable() = onEnable()

    internal fun invokeDisable() = onDisable()

    private fun runtime(): EternalScriptRuntimeBridge =
        runtimeBridge ?: when (runtimeState) {
            RuntimeState.CONSTRUCTING -> error(
                "EternalScript APIs are unavailable during construction. " +
                    "Move plugin, events, commands, task, launch, and async calls to onEnable()."
            )
            RuntimeState.ATTACHED -> error("The EternalScript runtime bridge is unavailable.")
            RuntimeState.DISPOSED -> error(
                "This EternalScript instance has been disposed; its runtime APIs can no longer be used."
            )
        }

    private enum class RuntimeState {
        CONSTRUCTING,
        ATTACHED,
        DISPOSED
    }
}
