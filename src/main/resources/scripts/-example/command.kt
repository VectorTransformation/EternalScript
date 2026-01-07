/**
 *
 * command
 *
 */

register("test-command") {
    aliases("t1", "t2")
    permission(null)
    tabCompleter { _, _, _ ->
        emptyList()
    }
    executor { sender, _, _ ->
        val name = sender.name
        Bukkit.broadcastMessage("sender: $name")
    }
}