/**
 *
 * event
 *
 */
event<PlayerJumpEvent> { event ->
    Bukkit.broadcastMessage("jump: ${event.player.name}")
}