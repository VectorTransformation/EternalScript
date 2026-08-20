package eternalscript.scripting.runtime

import eternalscript.feedback.FeedbackKey
import eternalscript.feedback.FeedbackLevel
import eternalscript.feedback.SystemFeedback
import eternalscript.feedback.systemFeedback
import eternalscript.scripting.runtime.declaration.ScriptCommandDefinition
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale

internal class ScriptCommandRegistry(
    private val plugin: JavaPlugin,
    private val system: (SystemFeedback) -> Unit
) {
    private val commandMap: CommandMap = plugin.server.commandMap
    private val knownCommands: MutableMap<String, Command> = commandMap.knownCommands
    private val prefix = plugin.name.lowercase(Locale.ROOT)
    private var batchDepth = 0
    private var syncPending = false

    fun <T> batch(block: () -> T): T {
        beginBatch()
        return try {
            block()
        } finally {
            endBatch()
        }
    }

    fun activate(
        definitions: List<ScriptCommandDefinition>,
        execution: ScriptExecutionHandle
    ): ScriptCommandRegistration {
        val commands = definitions.map { definition -> ScriptCommand(definition, execution) }
        validateDefinitions(commands)
        val registered = mutableListOf<RegisteredScriptCommand>()
        try {
            commands.forEach { command -> registered += register(command) }
            return ScriptCommandRegistration(this, registered)
        } catch (error: Throwable) {
            registered.asReversed().forEach { entry ->
                runCatching { unregister(entry) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    fun dispose(registrations: List<RegisteredScriptCommand>) {
        val failures = mutableListOf<Throwable>()
        registrations.asReversed().forEach { registration ->
            runCatching { unregister(registration) }.exceptionOrNull()?.let(failures::add)
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun register(command: ScriptCommand): RegisteredScriptCommand {
        val requestedKeys = commandKeys(command)
        val conflict = requestedKeys.firstOrNull(knownCommands::containsKey)
        check(conflict == null) { "Command label or alias is already registered: $conflict" }

        val registeredDirectly = try {
            commandMap.register(command.name, prefix, command)
        } catch (error: Throwable) {
            removeOwnedMappings(command)
            throw error
        }
        val ownedKeys = knownCommands
            .filterValues { registered -> registered === command }
            .keys
            .toSet()
        val missingKeys = requestedKeys - ownedKeys
        if (!registeredDirectly || missingKeys.isNotEmpty()) {
            runCatching { unregister(RegisteredScriptCommand(command, ownedKeys)) }
            val reason = if (!registeredDirectly) {
                "the primary label required a fallback prefix"
            } else {
                "labels were not registered: ${missingKeys.joinToString()}"
            }
            error("Could not register script command '${command.name}': $reason")
        }

        command.activate()
        requestSync()
        return RegisteredScriptCommand(command, ownedKeys)
    }

    private fun unregister(registration: RegisteredScriptCommand) {
        val command = registration.command
        command.deactivate()
        registration.keys.forEach { key ->
            if (knownCommands[key] === command) knownCommands.remove(key)
        }
        command.unregister(commandMap)
        requestSync()
    }

    private fun removeOwnedMappings(command: ScriptCommand) {
        command.deactivate()
        knownCommands.entries.removeIf { (_, registered) -> registered === command }
        runCatching { command.unregister(commandMap) }
        requestSync()
    }

    private fun validateDefinitions(commands: List<ScriptCommand>) {
        val seen = mutableSetOf<String>()
        commands.forEach { command ->
            val duplicate = commandKeys(command).firstOrNull { key -> !seen.add(key) }
            check(duplicate == null) { "Duplicate script command label or alias: $duplicate" }
        }
    }

    private fun commandKeys(command: ScriptCommand): Set<String> =
        (listOf(command.name) + command.aliases)
            .flatMap { label -> listOf(label, "$prefix:$label") }
            .map { label -> label.lowercase(Locale.ROOT) }
            .toSet()

    private fun beginBatch() {
        batchDepth++
    }

    private fun endBatch() {
        check(batchDepth > 0) { "No script command batch is active" }
        batchDepth--
        if (batchDepth == 0 && syncPending) {
            syncPending = false
            syncCommands()
        }
    }

    private fun requestSync() {
        if (batchDepth > 0) {
            syncPending = true
        } else {
            syncCommands()
        }
    }

    private fun syncCommands() {
        plugin.server.onlinePlayers.forEach { player ->
            runCatching(player::updateCommands).onFailure { error ->
                system(
                    systemFeedback(
                        FeedbackLevel.WARNING,
                        FeedbackKey.SYSTEM_COMMAND_TREE_REFRESH_FAILED,
                        "player" to player.name,
                        "error" to (error.message ?: error.javaClass.name)
                    )
                )
            }
        }
    }
}

internal data class RegisteredScriptCommand(
    val command: ScriptCommand,
    val keys: Set<String>
)

internal class ScriptCommandRegistration(
    private val registry: ScriptCommandRegistry,
    private val registrations: List<RegisteredScriptCommand>
) {
    private var disposed = false

    fun dispose() {
        if (disposed) return
        disposed = true
        registry.dispose(registrations)
    }
}

internal class ScriptCommand(
    private val definition: ScriptCommandDefinition,
    private val execution: ScriptExecutionHandle
) : Command(definition.name) {
    @Volatile
    private var active = false

    init {
        aliases = definition.aliases
        permission = definition.permission
    }

    fun activate() {
        active = true
    }

    fun deactivate() {
        active = false
    }

    override fun tabComplete(
        sender: CommandSender,
        alias: String,
        args: Array<String>
    ): List<String> = if (active && testPermissionSilent(sender)) {
        execution.executeOrNull { definition.tabCompleter(sender, alias, args.toList()) }.orEmpty()
    } else {
        emptyList()
    }

    override fun execute(
        sender: CommandSender,
        label: String,
        args: Array<String>
    ): Boolean {
        if (!active) return false
        if (!testPermission(sender)) return true
        return execution.tryExecute { definition.executor(sender, label, args.toList()) }
    }
}
