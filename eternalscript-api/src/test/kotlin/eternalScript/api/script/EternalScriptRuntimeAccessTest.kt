package eternalScript.api.script

import eternalScript.api.script.command.ScriptCommandDefinition
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Proxy
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(InternalEternalScriptRuntimeApi::class)
class EternalScriptRuntimeAccessTest {
    @Test
    fun `constructor API call is rejected with migration guidance`() {
        val failure = assertFailsWith<IllegalStateException> {
            ConstructorRegistrationScript()
        }

        assertTrue(failure.message.orEmpty().contains("construction"))
        assertTrue(failure.message.orEmpty().contains("onEnable"))
    }

    @Test
    fun `bridge is attached for enable cycles and detached after disposal`() {
        val script = EnableProbeScript()
        val bridge = RecordingBridge()

        EternalScriptRuntimeAccess.attach(script, bridge)
        assertSame(bridge.plugin, script.plugin)

        EternalScriptRuntimeAccess.enable(script)
        EternalScriptRuntimeAccess.disable(script)
        EternalScriptRuntimeAccess.enable(script)

        assertEquals(2, script.enableCount)
        assertEquals(1, script.disableCount)
        assertEquals(
            listOf("cycle-1", "cycle-2"),
            bridge.commands.map(ScriptCommandDefinition::name)
        )
        assertTrue(bridge.commands[0] !== bridge.commands[1])

        EternalScriptRuntimeAccess.detach(script, bridge)
        val failure = assertFailsWith<IllegalStateException> {
            script.commands {
                command("after-dispose") {}
            }
        }
        assertTrue(failure.message.orEmpty().contains("disposed"))
    }

    private class ConstructorRegistrationScript : EternalScript() {
        init {
            commands {
                command("constructor") {}
            }
        }
    }

    private class EnableProbeScript : EternalScript() {
        var enableCount = 0
        var disableCount = 0

        override fun onEnable() {
            enableCount += 1
            commands {
                command("cycle-$enableCount") {}
            }
        }

        override fun onDisable() {
            disableCount += 1
        }
    }

    private class RecordingBridge : EternalScriptRuntimeBridge {
        override val plugin: Plugin = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "TestPlugin"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        } as Plugin
        val commands = mutableListOf<ScriptCommandDefinition>()

        override fun <T : Event> event(
            event: KClass<T>,
            priority: EventPriority,
            block: (T) -> Unit
        ) = Unit

        override fun command(definition: ScriptCommandDefinition) {
            commands += definition
        }

        override fun <T : Job> track(job: T): T = job

        override fun <T : BukkitTask> track(task: T): T = task

        override fun <T : ScheduledTask> track(task: T): T = task

        override fun task(block: () -> Unit): Runnable = Runnable(block)

        override fun <T> task(block: (T) -> Unit): Consumer<T> = Consumer { value ->
            block(value)
        }

        override fun launch(
            context: CoroutineContext,
            block: suspend CoroutineScope.() -> Unit
        ): Job = Job()

        override fun <T> async(
            context: CoroutineContext,
            block: suspend CoroutineScope.() -> T
        ): Deferred<T> = CompletableDeferred()
    }
}
