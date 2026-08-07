package eternalScript.core.script.runtime

import eternalScript.core.script.command.ScriptCommand
import eternalScript.api.script.command.ScriptCommandBuilder
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import eternalScript.core.the.Root
import org.bukkit.command.Command

internal class ScriptCommandRegistry(
    private val executionGate: ScriptExecutionGate,
    private val registrationGate: ScriptRegistrationGate,
    private val commandLookup: (String) -> Command? = { key -> commandMap.getCommand(key) },
    private val commandRegistrar: (Command) -> Boolean = { command ->
        commandMap.register(command.name, prefix, command)
    },
    private val commandRemover: (Command) -> Unit = ::removeCommand,
    private val commandUpdater: () -> Unit = ::updateOnlineCommands
) {
    private val registrations = ScriptRegistrationLifecycle<Command>()

    internal fun beginActivation() {
        registrations.beginActivation()
    }

    fun addCommand(builder: ScriptCommandBuilder) {
        val commandKeys = commandKeys(builder.name, builder.aliases)
        check(
            commands().none { command ->
                commandKeys.overlaps(commandKeys(command.name, command.aliases))
            }
        ) {
            "Command ${builder.name} conflicts with another command in the same Script."
        }
        val command = ScriptCommand(builder, executionGate)
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
        val occupied = commandKeys(command.name, command.aliases)
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

    companion object {
        private val commandMap by lazy { Root.INSTANCE.server.commandMap }
        private val knownCommands by lazy { commandMap.knownCommands }
        private val prefix = Root.ORIGIN.lowercase()

        private fun commandKeys(name: String, aliases: List<String>) =
            (listOf(name) + aliases).flatMap { key ->
                listOf(key, "$prefix:$key")
            }

        private fun removeCommand(command: Command) {
            commandKeys(command.name, command.aliases).forEach { key ->
                if (knownCommands[key] === command) {
                    knownCommands.remove(key)
                }
            }
            command.unregister(commandMap)
        }

        private fun updateOnlineCommands() {
            Root.onlinePlayers().forEach { player ->
                player.scheduler.run(
                    Root.INSTANCE,
                    { player.updateCommands() },
                    null
                )
            }
        }

        private fun List<String>.overlaps(other: List<String>): Boolean =
            any { candidate ->
                other.any { existing -> candidate.equals(existing, ignoreCase = true) }
            }
    }
}
