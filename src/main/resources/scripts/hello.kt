/**
 *
 * Hello, world!
 *
 */
enable {
    Bukkit.broadcastMessage("Hello, world!")
}

event<PlayerJoinEvent> { event ->
    Bukkit.broadcastMessage("Hello, ${event.player.name}!")
}