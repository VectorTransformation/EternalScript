package eternalScript.core.script.runtime

import eternalScript.api.script.EternalScript
import eternalScript.api.script.EternalScriptRuntimeAccess
import eternalScript.api.script.EternalScriptRuntimeBridge
import eternalScript.api.script.InternalEternalScriptRuntimeApi
import eternalScript.api.script.command.ScriptCommandBuilder
import eternalScript.core.script.classloading.ScriptContextClassLoaderElement
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import eternalScript.core.script.generation.ScriptInstanceLifecycleState
import eternalScript.core.script.generation.cleanup
import eternalScript.core.script.generation.throwCombined
import eternalScript.core.the.Root
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/** Core-owned runtime state behind one public [EternalScript] entry. */
@OptIn(InternalEternalScriptRuntimeApi::class)
internal class ManagedScriptRuntime(
    val script: EternalScript,
    private val pluginProvider: () -> Plugin = { Root.INSTANCE }
) : EternalScriptRuntimeBridge {
    internal val executionGate = ScriptExecutionGate()
    private val registrationGate = ScriptRegistrationGate()
    private val lifecycle = ScriptInstanceLifecycleState()
    internal val commandRegistry = ScriptCommandRegistry(executionGate, registrationGate)
    internal val listenerRegistry = ScriptListenerRegistry(executionGate, registrationGate)
    internal val taskScope = ScriptTaskScope()

    init {
        EternalScriptRuntimeAccess.attach(script, this)
    }

    override val plugin: Plugin
        get() = pluginProvider()

    override fun <T : Event> event(
        event: KClass<T>,
        priority: EventPriority,
        block: (T) -> Unit
    ) {
        listenerRegistry.add(event, priority, block)
    }

    override fun command(builder: ScriptCommandBuilder) {
        commandRegistry.addCommand(builder)
    }

    override fun <T : Job> track(job: T): T = taskScope.track(job)

    override fun <T : BukkitTask> track(task: T): T = taskScope.track(task)

    override fun <T : ScheduledTask> track(task: T): T = taskScope.track(task)

    override fun task(block: () -> Unit): Runnable = Runnable {
        executionGate.withActive(block)
    }

    override fun <T> task(block: (T) -> Unit): Consumer<T> = Consumer { value ->
        executionGate.withActive {
            block(value)
        }
    }

    override fun launch(
        context: CoroutineContext,
        block: suspend CoroutineScope.() -> Unit
    ): Job = taskScope.trackAndStart(
        Root.launch(
            context = context + ScriptContextClassLoaderElement(executionGate),
            start = CoroutineStart.LAZY,
            block = block
        )
    )

    override fun <T> async(
        context: CoroutineContext,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = taskScope.trackAndStart(
        Root.async(
            context = context + ScriptContextClassLoaderElement(executionGate),
            start = CoroutineStart.LAZY,
            block = block
        )
    )

    fun activate() {
        executionGate.withContext {
            lifecycle.activate {
                taskScope.open()
                listenerRegistry.beginActivation()
                commandRegistry.beginActivation()
                registrationGate.withOpen {
                    EternalScriptRuntimeAccess.enable(script)
                }
                listenerRegistry.register()
                commandRegistry.register()
            }
        }
    }

    fun deactivate(failures: MutableList<Throwable>) {
        executionGate.withContext {
            lifecycle.deactivate {
                taskScope.close()
                cleanup(failures) {
                    EternalScriptRuntimeAccess.disable(script)
                }
                cleanup(failures, taskScope::clear)
                cleanup(failures, listenerRegistry::unregister)
                cleanup(failures, commandRegistry::unregister)
            }
        }
    }

    fun dispose(failures: MutableList<Throwable>) {
        executionGate.withContext {
            cleanup(failures, taskScope::clear)
            cleanup(failures, listenerRegistry::clear)
            cleanup(failures, commandRegistry::clear)
            cleanup(failures) {
                EternalScriptRuntimeAccess.detach(script, this)
            }
        }
    }

    fun disposeRuntime() {
        val failures = mutableListOf<Throwable>()
        dispose(failures)
        failures.throwCombined()
    }
}
