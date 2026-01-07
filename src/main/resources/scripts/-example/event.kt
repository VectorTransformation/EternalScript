/**
 *
 * event
 *
 */

register<PlayerJoinEvent> { event ->
    val name = event.player.name
    Bukkit.broadcastMessage("join: $name")
}

register<PlayerQuitEvent> { event ->
    val name = event.player.name
    Bukkit.broadcastMessage("quit: $name")
}