package eternalScript.core.script

import eternalScript.core.script.data.ScriptLifecycle
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import eternalScript.core.manager.ScriptManager
import eternalScript.core.script.command.ScriptCommandBuilder
import eternalScript.core.script.manager.ScriptCommandManager
import eternalScript.core.script.manager.ScriptFunctionManager
import eternalScript.core.script.manager.ScriptListenerManager
import eternalScript.core.script.manager.ScriptTaskManager
import eternalScript.core.script.project.ScriptProjectFunctionRegistry
import eternalScript.core.script.project.ScriptProjectFunctionInvocation
import eternalScript.core.the.Root
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.Job
import org.bukkit.scheduler.BukkitTask
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import java.util.concurrent.atomic.AtomicReference

abstract class Script {
    val executionGate = ScriptExecutionGate()
    internal val registrationGate = ScriptRegistrationGate()
    internal val functionManager = ScriptFunctionManager()
    internal val commandManager = ScriptCommandManager(executionGate, registrationGate)
    @PublishedApi
    internal val listenerManager = ScriptListenerManager(executionGate, registrationGate)
    internal val taskManager = ScriptTaskManager()
    private val runtimeResource = ScriptRuntimeResource()
    private val projectFunctions = AtomicReference<ScriptProjectFunctionRegistry?>()

    internal fun attachRuntimeResource(resource: AutoCloseable) {
        runtimeResource.attach(resource)
    }

    internal fun closeRuntimeResource() {
        runtimeResource.close()
    }

    internal fun attachProjectFunctions(classes: Iterable<Class<*>>) {
        val registry = ScriptProjectFunctionRegistry.create(classes)
        check(projectFunctions.compareAndSet(null, registry)) {
            "Project functions are already attached to this script generation."
        }
    }

    internal fun projectFunctionNames(): List<String> =
        projectFunctions.get()?.zeroArgumentNames().orEmpty()

    internal fun callProjectFunction(
        name: String,
        vararg args: Any?
    ): ScriptProjectFunctionInvocation? =
        projectFunctions.get()?.call(name, *args)

    internal fun clearProjectFunctions() {
        projectFunctions.getAndSet(null)?.clear()
    }

    internal fun disposeRuntime() {
        val failures = mutableListOf<Throwable>()
        dispose(failures, taskManager::clear)
        dispose(failures, listenerManager::clear)
        dispose(failures, commandManager::clear)
        dispose(failures, functionManager::clear)
        dispose(failures, ::clearProjectFunctions)
        dispose(failures, ::closeRuntimeResource)

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

    // lifecycle

    fun enable(block: () -> Unit) {
        functionManager.save(ScriptLifecycle.ENABLE, block)
    }

    fun disable(block: () -> Unit) {
        functionManager.save(ScriptLifecycle.DISABLE, block)
    }

    // event

    inline fun <reified T : Event> event(
        priority: EventPriority = EventPriority.NORMAL,
        noinline block: (T) -> Unit
    ) {
        register(priority, block)
    }

    inline fun <reified T : Event> register(
        priority: EventPriority = EventPriority.NORMAL,
        noinline block: (T) -> Unit
    ) {
        listenerManager.add(T::class, priority, block)
    }

    // command

    fun command(name: String, block: ScriptCommandBuilder.() -> Unit) {
        register(name, block)
    }

    fun register(name: String, block: ScriptCommandBuilder.() -> Unit) {
        commandManager.addCommand(ScriptCommandBuilder(name).apply(block))
    }

    // task

    fun <T : Job> track(job: T) = taskManager.track(job)

    fun <T : BukkitTask> track(task: T) = taskManager.track(task)

    fun <T : ScheduledTask> track(task: T) = taskManager.track(task)

    // util

    fun instance() = Root.INSTANCE

    fun scripts() = ScriptManager.scripts()

    @Deprecated(
        message = "All source files now belong to one project generation. Use scripts(), functions(), or call()."
    )
    @Suppress("DEPRECATION")
    fun script(script: String) = ScriptManager.script(script)
}
