/**
 * Typed event listeners are owned by this script and removed automatically on unload.
 * MONITOR observes the completed event without modifying it.
 */

import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

on<PlayerJoinEvent>(priority = EventPriority.MONITOR) { event ->
    notify(event.player).info("Join event observed for ${event.player.name}")
}

on<PlayerQuitEvent>(priority = EventPriority.MONITOR) { event ->
    notify().info("Quit event observed for ${event.player.name}")
}
