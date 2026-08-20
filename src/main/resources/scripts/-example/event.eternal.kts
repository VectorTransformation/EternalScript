/**
 * Typed event listeners are owned by this script and removed automatically on unload.
 * MONITOR observes the completed event without modifying it.
 */

import org.bukkit.event.EventPriority

on<PlayerJoinEvent>(priority = EventPriority.MONITOR) { event ->
    feedback(
        event.player,
        "Join event observed for ${event.player.name}",
        ScriptFeedbackLevel.INFO
    )
}

on<PlayerQuitEvent>(priority = EventPriority.MONITOR) { event ->
    feedback(
        Bukkit.getConsoleSender(),
        "Quit event observed for ${event.player.name}",
        ScriptFeedbackLevel.INFO
    )
}
