package eternalScript.core.script.runtime

import eternalScript.api.script.InternalEternalScriptRuntimeApi
import eternalScript.api.script.command.ScriptCommandDefinition
import eternalScript.core.script.command.ScriptCommand
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(InternalEternalScriptRuntimeApi::class)
class ScriptCommandRegistryTest {
    @Test
    fun `rollback rebuilds activation commands without duplicating definitions`() {
        val gate = ScriptRegistrationGate()
        val registered = mutableListOf<String>()
        val registry = registry(
            gate = gate,
            registrar = { command ->
                registered += command.name
                true
            }
        )
        registry.beginActivation()
        gate.withOpen {
            registry.addCommand(command("cycle"))
        }
        registry.register()
        assertEquals(listOf("cycle"), registered)
        registry.unregister()

        registered.clear()
        registry.beginActivation()
        gate.withOpen {
            registry.addCommand(command("cycle"))
        }
        registry.register()

        assertEquals(listOf("cycle"), registered)
    }

    @Test
    fun `same activation rejects overlapping command keys`() {
        val gate = ScriptRegistrationGate()
        val registry = registry(gate)
        registry.beginActivation()

        gate.withOpen {
            registry.addCommand(
                command("first", "shared")
            )
            assertFailsWith<IllegalStateException> {
                registry.addCommand(command("shared"))
            }
        }
    }

    @Test
    fun `activation rejects a command key occupied by another plugin`() {
        val gate = ScriptRegistrationGate()
        val occupied = object : Command("external") {
            override fun execute(
                sender: CommandSender,
                commandLabel: String,
                args: Array<out String>
            ): Boolean = true
        }
        val registry = registry(
            gate = gate,
            lookup = { key -> occupied.takeIf { key.equals("external", ignoreCase = true) } }
        )
        registry.beginActivation()

        gate.withOpen {
            registry.addCommand(command("external"))
        }
        assertFailsWith<IllegalStateException> {
            registry.register()
        }
    }

    @Test
    fun `staged command ignores previous generation until registration`() {
        val gate = ScriptRegistrationGate()
        var occupied: Command? = ScriptCommand(
            command("reloadable"),
            ScriptExecutionGate()
        )
        val registered = mutableListOf<String>()
        val registry = registry(
            gate = gate,
            lookup = { key ->
                occupied.takeIf { key.equals("reloadable", ignoreCase = true) }
            },
            registrar = { command ->
                registered += command.name
                true
            }
        )

        registry.beginActivation()
        gate.withOpen {
            registry.addCommand(command("reloadable"))
        }
        occupied = null
        registry.register()

        assertEquals(listOf("reloadable"), registered)
    }

    @Test
    fun `activation rejects a command registered by an earlier entry`() {
        val gate = ScriptRegistrationGate()
        val occupied = ScriptCommand(
            command("shared"),
            ScriptExecutionGate()
        )
        val registry = registry(
            gate = gate,
            lookup = { key -> occupied.takeIf { key.equals("shared", ignoreCase = true) } }
        )

        registry.beginActivation()
        gate.withOpen {
            registry.addCommand(command("shared"))
        }

        assertFailsWith<IllegalStateException> {
            registry.register()
        }
    }

    @Test
    fun `background activation coroutine cannot register a command`() {
        val gate = ScriptRegistrationGate()
        val registry = registry(gate)
        registry.beginActivation()

        assertFailsWith<IllegalStateException> {
            registry.addCommand(command("background"))
        }
    }

    @Test
    fun `command registration before onEnable is rejected`() {
        val registry = registry(ScriptRegistrationGate())

        assertFailsWith<IllegalStateException> {
            registry.addCommand(command("constructor"))
        }
    }

    @Test
    fun `command map registration failure fails activation`() {
        val gate = ScriptRegistrationGate()
        var removals = 0
        val registry = registry(
            gate = gate,
            registrar = { false },
            remover = { removals += 1 }
        )
        registry.beginActivation()
        gate.withOpen {
            registry.addCommand(command("rejected"))
        }

        assertFailsWith<IllegalStateException> {
            registry.register()
        }
        registry.unregister()
        assertEquals(1, removals)
    }

    private fun registry(
        gate: ScriptRegistrationGate,
        lookup: (String) -> Command? = { null },
        registrar: (Command) -> Boolean = { true },
        remover: (Command) -> Unit = {}
    ) = ScriptCommandRegistry(
        executionGate = ScriptExecutionGate(),
        registrationGate = gate,
        commandLookup = lookup,
        commandRegistrar = registrar,
        commandRemover = remover,
        commandUpdater = {}
    )

    private fun command(
        name: String,
        vararg aliases: String
    ) = ScriptCommandDefinition(name = name, aliases = aliases.toList())
}
