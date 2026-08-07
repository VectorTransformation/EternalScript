package eternalScript.core.script.runtime

import eternalScript.api.script.InternalEternalScriptRuntimeApi
import eternalScript.api.script.command.ScriptCommandDefinition
import eternalScript.core.script.command.ScriptCommand
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import eternalScript.core.runtime.PLUGIN_NAME
import eternalScript.core.runtime.ServerAccess
import org.bukkit.command.Command

@OptIn(InternalEternalScriptRuntimeApi::class)
internal class ScriptCommandRegistry(
    private val executionGate: ScriptExecutionGate,
    private val registrationGate: ScriptRegistrationGate,
    server: ServerAccess? = null,
    private val commandLookup: (String) -> Command? = { key ->
        checkNotNull(server) { "Server access is required to register script commands." }
            .command(key)
    },
    private val commandRegistrar: (Command) -> Boolean = { command ->
        checkNotNull(server) { "Server access is required to register script commands." }
            .registerScriptCommand(command)
    },
    private val commandRemover: (Command) -> Unit = { command ->
        checkNotNull(server) { "Server access is required to remove script commands." }
            .removeScriptCommand(command)
    },
    private val commandUpdater: () -> Unit = {
        checkNotNull(server) { "Server access is required to update script commands." }
            .updateOnlineCommands()
    }
) {
    private val registrations = ScriptRegistrationLifecycle<Command>()

    internal fun beginActivation() {
        registrations.beginActivation()
    }

    fun addCommand(definition: ScriptCommandDefinition) {
        val commandKeys = scriptCommandKeys(definition.name, definition.aliases)
        check(
            commands().none { command ->
                commandKeys.overlaps(scriptCommandKeys(command.name, command.aliases))
            }
        ) {
            "Command ${definition.name} conflicts with another command in the " +
                "same EternalScript entry."
        }
        val command = ScriptCommand(definition, executionGate)
        if (
            registrations.add(
                command,
                registrationGateOpen = registrationGate.isOpen
            ) == ScriptRegistrationLifecycle.Placement.LIVE
        ) {
            requireCommandKeysAvailable(command)
            check(commandRegistrar(command)) {
                "Command ${command.name} could not be registered."
            }
            updateCommands()
        }
    }

    fun register() {
        val commands = registrations.activate()
        commands.forEach { command ->
            requireCommandKeysAvailable(command)
            check(commandRegistrar(command)) {
                "Command ${command.name} could not be registered."
            }
        }
        if (commands.isNotEmpty()) updateCommands()
    }

    fun unregister() {
        val release = registrations.deactivate()
        if (!release.wasActive) return
        val registered = release.registrations
        registered.forEach { command ->
            runCatching { commandRemover(command) }
        }
        if (registered.isNotEmpty()) {
            updateCommands()
        }
    }

    fun clear() {
        val release = registrations.dispose()
        if (!release.wasActive) return
        release.registrations.forEach { command ->
            runCatching { commandRemover(command) }
        }
        if (release.registrations.isNotEmpty()) {
            updateCommands()
        }
    }

    private fun commands() = registrations.snapshot()

    private fun requireCommandKeysAvailable(command: Command) {
        val occupied = scriptCommandKeys(command.name, command.aliases)
            .mapNotNull(commandLookup)
            .firstOrNull()
            ?: return
        val owner = if (occupied is ScriptCommand) {
            "another EternalScript entry"
        } else {
            "an existing server command"
        }
        error("Command ${command.name} conflicts with $owner (${occupied.name}).")
    }

    fun updateCommands() {
        commandUpdater()
    }

}

private fun scriptCommandKeys(name: String, aliases: List<String>) =
    (listOf(name) + aliases).flatMap { key ->
        listOf(key, "${PLUGIN_NAME.lowercase()}:$key")
    }

private fun List<String>.overlaps(other: List<String>): Boolean =
    any { candidate ->
        other.any { existing -> candidate.equals(existing, ignoreCase = true) }
    }
