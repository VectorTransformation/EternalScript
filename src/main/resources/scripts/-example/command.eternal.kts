/**
 * A reload-owned command with an alias, suggestions, and structured feedback.
 * Enable the disabled example directory with `/es load example`.
 */

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
        feedback(
            sender,
            ScriptFeedbackMessage(
                title = "Example command completed",
                details = listOf(
                    "Sender: ${sender.name}",
                    "Label: /$label",
                    "Arguments: $arguments"
                ),
                hint = "Try /$label hello"
            ),
            ScriptFeedbackLevel.SUCCESS
        )
    }
}
