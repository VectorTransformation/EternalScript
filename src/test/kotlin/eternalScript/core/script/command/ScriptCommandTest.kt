package eternalScript.core.script.command

import eternalScript.api.script.InternalEternalScriptRuntimeApi
import eternalScript.api.script.command.ScriptCommandDefinition
import eternalScript.core.script.data.ScriptExecutionGate
import org.bukkit.command.CommandSender
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalEternalScriptRuntimeApi::class)
class ScriptCommandTest {
    @Test
    fun `Bukkit callbacks are mapped to immutable command contexts`() {
        val gate = ScriptExecutionGate()
        assertTrue(gate.publish())
        var execution = ""
        val command = ScriptCommand(
            ScriptCommandDefinition(
                name = "probe",
                suggestions = {
                    listOf("${sender.name}:$alias:${arguments.single()}")
                },
                execution = {
                    execution =
                        "${sender.name}:$label:${arguments.joinToString()}"
                }
            ),
            gate
        )
        val sender = commandSender("Console")

        assertEquals(
            listOf("Console:p:one"),
            command.tabComplete(sender, "p", arrayOf("one"))
        )
        assertTrue(command.execute(sender, "probe", arrayOf("two", "three")))
        assertEquals("Console:probe:two, three", execution)
    }

    @Suppress("UNCHECKED_CAST")
    private fun commandSender(name: String): CommandSender =
        Proxy.newProxyInstance(
            CommandSender::class.java.classLoader,
            arrayOf(CommandSender::class.java)
        ) { instance, method, args ->
            when (method.name) {
                "getName" -> name
                "toString" -> name
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                else -> null
            }
        } as CommandSender
}
