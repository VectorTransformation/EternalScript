package eternalScript.core.script.runtime

import eternalScript.core.script.command.ScriptCommand
import eternalScript.core.script.command.ScriptCommandBuilder
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        registry.addCommand(ScriptCommandBuilder("constructor"))

        registry.beginActivation()
        gate.withOpen {
            registry.addCommand(ScriptCommandBuilder("cycle"))
        }
        registry.register()
        assertEquals(listOf("constructor", "cycle"), registered)
        registry.unregister()

        registered.clear()
        registry.beginActivation()
        gate.withOpen {
            registry.addCommand(ScriptCommandBuilder("cycle"))
        }
        registry.register()

        assertEquals(listOf("constructor", "cycle"), registered)
    }

    @Test
    fun `same activation rejects overlapping command keys`() {
        val gate = ScriptRegistrationGate()
        val registry = registry(gate)
        registry.beginActivation()

        gate.withOpen {
            registry.addCommand(
                ScriptCommandBuilder("first").apply { aliases("shared") }
            )
            assertFailsWith<IllegalStateException> {
                registry.addCommand(ScriptCommandBuilder("shared"))
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
            registry.addCommand(ScriptCommandBuilder("external"))
        }
        assertFailsWith<IllegalStateException> {
            registry.register()
        }
    }

    @Test
    fun `staged command ignores previous generation until registration`() {
        val gate = ScriptRegistrationGate()
        var occupied: Command? = ScriptCommand(
            ScriptCommandBuilder("reloadable"),
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

        registry.addCommand(ScriptCommandBuilder("reloadable"))
        occupied = null
        registry.beginActivation()
        registry.register()

        assertEquals(listOf("reloadable"), registered)
    }

    @Test
    fun `activation rejects a command registered by an earlier entry`() {
        val gate = ScriptRegistrationGate()
        val occupied = ScriptCommand(
            ScriptCommandBuilder("shared"),
            ScriptExecutionGate()
        )
        val registry = registry(
            gate = gate,
            lookup = { key -> occupied.takeIf { key.equals("shared", ignoreCase = true) } }
        )

        registry.addCommand(ScriptCommandBuilder("shared"))
        registry.beginActivation()

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
            registry.addCommand(ScriptCommandBuilder("background"))
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
            registry.addCommand(ScriptCommandBuilder("rejected"))
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
}
