package eternalScript.api.script

import eternalScript.core.script.classloading.ScriptContextClassLoaderElement
import eternalScript.core.script.command.ScriptCommandBuilder
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptLifecycle
import eternalScript.core.script.data.ScriptRegistrationGate
import eternalScript.core.script.runtime.ScriptCommandRegistry
import eternalScript.core.script.runtime.ScriptLifecycleRegistry
import eternalScript.core.script.runtime.ScriptListenerRegistry
import eternalScript.core.script.runtime.ScriptTaskScope
import eternalScript.core.the.Root
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext

/**
 * Managed API surface shared by every [EternalScript] entry.
 *
 * Listeners, commands, coroutines, and scheduler tasks registered through this
 * class belong to the current project generation and are cleaned up when that
 * generation is replaced or unloaded.
 */
abstract class Script internal constructor() {
    internal val executionGate = ScriptExecutionGate()
    internal val registrationGate = ScriptRegistrationGate()
    internal val lifecycleRegistry = ScriptLifecycleRegistry()
    internal val commandRegistry = ScriptCommandRegistry(executionGate, registrationGate)
    @PublishedApi
    internal val listenerRegistry = ScriptListenerRegistry(executionGate, registrationGate)
    internal val taskScope = ScriptTaskScope()

    /** The EternalScript plugin instance for Paper and Folia scheduler APIs. */
    val plugin: Plugin
        get() = Root.INSTANCE

    internal fun disposeRuntime() {
        val failures = mutableListOf<Throwable>()
        dispose(failures, taskScope::clear)
        dispose(failures, listenerRegistry::clear)
        dispose(failures, commandRegistry::clear)
        dispose(failures, lifecycleRegistry::clear)

        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun dispose(failures: MutableList<Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (exception: Throwable) {
            failures.add(exception)
        }
    }

    internal fun onEnable(block: () -> Unit) {
        lifecycleRegistry.save(ScriptLifecycle.ENABLE, block)
    }

    internal fun onDisable(block: () -> Unit) {
        lifecycleRegistry.save(ScriptLifecycle.DISABLE, block)
    }

    inline fun <reified T : Event> event(
        priority: EventPriority = EventPriority.NORMAL,
        noinline block: (T) -> Unit
    ) {
        listenerRegistry.add(T::class, priority, block)
    }

    fun command(name: String, block: ScriptCommandBuilder.() -> Unit) {
        commandRegistry.addCommand(ScriptCommandBuilder(name).apply(block))
    }

    fun <T : Job> track(job: T) = taskScope.track(job)

    fun <T : BukkitTask> track(task: T) = taskScope.track(task)

    fun <T : ScheduledTask> track(task: T) = taskScope.track(task)

    /**
     * Wraps a scheduler callback so it only enters the active generation and
     * uses that generation's context class loader.
     */
    fun task(block: () -> Unit): Runnable = Runnable {
        executionGate.withActive(block)
    }

    /** Scheduler callback counterpart for APIs that pass a task handle. */
    fun <T> task(block: (T) -> Unit): Consumer<T> = Consumer { value ->
        executionGate.withActive {
            block(value)
        }
    }

    /** Launches coroutine work owned and cancelled by the current generation. */
    fun launch(
        context: CoroutineContext = Dispatchers.Default,
        block: suspend CoroutineScope.() -> Unit
    ): Job = taskScope.trackAndStart(
        Root.launch(
            context = context + ScriptContextClassLoaderElement(executionGate),
            start = kotlinx.coroutines.CoroutineStart.LAZY,
            block = block
        )
    )

    /** Async counterpart to [launch]. */
    fun <T> async(
        context: CoroutineContext = Dispatchers.Default,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = taskScope.trackAndStart(
        Root.async(
            context = context + ScriptContextClassLoaderElement(executionGate),
            start = kotlinx.coroutines.CoroutineStart.LAZY,
            block = block
        )
    )
}
