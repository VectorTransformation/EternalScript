/**
 *
 * command /test-command
 *
 */
command("test-command") {
    executor = { sender, label, args ->
        Bukkit.broadcastMessage("command: ${sender.name}")
    }
}