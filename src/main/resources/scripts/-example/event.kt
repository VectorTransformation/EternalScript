/**
 *
 * event
 *
 */

event<PlayerJoinEvent> { event ->
    Bukkit.broadcastMessage("${event.player.name} joined the server!")
}

event<PlayerQuitEvent> { event ->
    Bukkit.broadcastMessage("${event.player.name} left the server.")
}