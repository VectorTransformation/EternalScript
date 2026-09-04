/**
 * A reload-owned command with an alias, suggestions, and a structured notification.
 * Enable the disabled example directory with `/es enable example`.
 */

import eternalscript.api.script.notification.ScriptNotification

command("example-command") {
    aliases("example")
    permission(null)
    tabCompleter { _, _, args ->
        val current = args.lastOrNull().orEmpty()
        listOf("hello", "world").filter { value ->
            value.startsWith(current, ignoreCase = true)
        }
    }
    executor { sender, label, args ->
        val arguments = args.joinToString().ifBlank { "(none)" }
        notify(sender).success(
            ScriptNotification(
                title = "Example command completed",
                details = listOf(
                    "Sender: ${sender.name}",
                    "Label: /$label",
                    "Arguments: $arguments"
                ),
                hint = "Try /$label hello"
            )
        )
    }
}
