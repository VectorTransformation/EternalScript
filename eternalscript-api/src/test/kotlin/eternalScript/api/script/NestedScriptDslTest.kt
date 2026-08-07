package eternalScript.api.script

import eternalScript.api.script.command.ScriptCommandContext
import eternalScript.api.script.command.ScriptCommandDefinition
import eternalScript.api.script.command.ScriptSuggestionContext
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import org.bukkit.command.CommandSender
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Proxy
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalEternalScriptRuntimeApi::class)
class NestedScriptDslTest {
    @Test
    fun `nested DSL publishes event and immutable command definitions`() {
        val script = DslProbeScript()
        val bridge = RecordingBridge()
        EternalScriptRuntimeAccess.attach(script, bridge)

        EternalScriptRuntimeAccess.enable(script)
        script.aliases[0] = "changed-after-build"

        assertEquals(ProbeEvent::class, bridge.events.single())
        val definition = bridge.commands.single()
        assertEquals("probe", definition.name)
        assertEquals(listOf("p"), definition.aliases)
        assertEquals("probe.use", definition.permission)

        val sender = commandSender("Console")
        assertEquals(
            listOf("Console:probe-alias:one"),
            definition.suggest(
                ScriptSuggestionContext(sender, "probe-alias", listOf("one"))
            )
        )
        definition.execute(
            ScriptCommandContext(sender, "probe-label", listOf("two"))
        )
        assertEquals("Console:probe-label:two:TestPlugin", script.execution)
    }

    private class DslProbeScript : EternalScript() {
        val aliases = arrayOf("p")
        var execution = ""

        override fun onEnable() {
            events {
                on<ProbeEvent> {
                    plugin.name
                }
            }
            commands {
                command("probe") {
                    aliases(*this@DslProbeScript.aliases)
                    permission("probe.use")
                    suggests {
                        listOf("${sender.name}:$alias:${arguments.single()}")
                    }
                    executes {
                        execution =
                            "${sender.name}:$label:${arguments.single()}:${plugin.name}"
                    }
                }
            }
        }
    }

    private class ProbeEvent : Event() {
        override fun getHandlers(): HandlerList = HANDLERS

        companion object {
            @JvmStatic
            val HANDLERS = HandlerList()
        }
    }

    private inner class RecordingBridge : EternalScriptRuntimeBridge {
        override val plugin: Plugin = proxy(Plugin::class.java) { method ->
            when (method.name) {
                "getName" -> "TestPlugin"
                else -> null
            }
        }
        val events = mutableListOf<KClass<out Event>>()
        val commands = mutableListOf<ScriptCommandDefinition>()

        override fun <T : Event> event(
            event: KClass<T>,
            priority: EventPriority,
            block: (T) -> Unit
        ) {
            events += event
        }

        override fun command(definition: ScriptCommandDefinition) {
            commands += definition
        }

        override fun <T : Job> track(job: T): T = job
        override fun <T : BukkitTask> track(task: T): T = task
        override fun <T : ScheduledTask> track(task: T): T = task
        override fun task(block: () -> Unit): Runnable = Runnable(block)
        override fun <T> task(block: (T) -> Unit): Consumer<T> = Consumer(block)
        override fun launch(
            context: CoroutineContext,
            block: suspend CoroutineScope.() -> Unit
        ): Job = Job()

        override fun <T> async(
            context: CoroutineContext,
            block: suspend CoroutineScope.() -> T
        ): Deferred<T> = CompletableDeferred()
    }

    private fun commandSender(name: String): CommandSender =
        proxy(CommandSender::class.java) { method ->
            when (method.name) {
                "getName" -> name
                else -> null
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(
        type: Class<T>,
        answer: (java.lang.reflect.Method) -> Any?
    ): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type)
    ) { instance, method, args ->
        when (method.name) {
            "toString" -> type.simpleName
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            else -> answer(method)
        }
    } as T
}
