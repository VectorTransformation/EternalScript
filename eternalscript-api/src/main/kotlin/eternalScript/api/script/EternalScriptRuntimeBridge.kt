package eternalScript.api.script

import eternalScript.api.script.command.ScriptCommandBuilder
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/** Runtime implementation behind one managed [EternalScript] instance. */
@InternalEternalScriptRuntimeApi
interface EternalScriptRuntimeBridge {
    val plugin: Plugin

    fun <T : Event> event(
        event: KClass<T>,
        priority: EventPriority,
        block: (T) -> Unit
    )

    fun command(builder: ScriptCommandBuilder)

    fun <T : Job> track(job: T): T

    fun <T : BukkitTask> track(task: T): T

    fun <T : ScheduledTask> track(task: T): T

    fun task(block: () -> Unit): Runnable

    fun <T> task(block: (T) -> Unit): Consumer<T>

    fun launch(
        context: CoroutineContext,
        block: suspend CoroutineScope.() -> Unit
    ): Job

    fun <T> async(
        context: CoroutineContext,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T>
}
