package eternalscript.api.script.command

import eternalscript.scripting.runtime.declaration.ScriptCommandDefinition
import org.bukkit.command.CommandSender

public class ScriptCommandBuilder(public val name: String) {
    private var executorConfigured: Boolean = false

    public var aliases: List<String> = emptyList()
    public var permission: String? = null
    public var tabCompleter: (sender: CommandSender, alias: String, args: List<String>) -> List<String> =
        { _, _, _ -> emptyList() }
    public var executor: (sender: CommandSender, label: String, args: List<String>) -> Unit =
        { _, _, _ -> error("Script command executor is not configured") }
        set(value) {
            field = value
            executorConfigured = true
        }

    public fun aliases(vararg alias: String) {
        aliases = alias.toList()
    }

    public fun permission(permission: String?) {
        this.permission = permission
    }

    public fun tabCompleter(
        block: (sender: CommandSender, alias: String, args: List<String>) -> List<String>
    ) {
        tabCompleter = block
    }

    public fun executor(
        block: (sender: CommandSender, alias: String, args: List<String>) -> Unit
    ) {
        executor = block
    }

    internal fun definition(): ScriptCommandDefinition {
        require(name.isNotBlank()) { "Script command name must not be blank" }
        require(aliases.none(String::isBlank)) { "Script command aliases must not be blank" }
        require(executorConfigured) { "Script command '$name' must define an executor" }
        return ScriptCommandDefinition(
            name,
            aliases.toList(),
            permission,
            tabCompleter,
            executor
        )
    }
}
