/**
 * Active starter script installed only when the scripts directory is first created.
 * Edit this file in place, or disable it with `/es disable hello.eternal.kts`.
 */

import org.bukkit.event.player.PlayerJoinEvent

onLoad {
    notify().success("EternalScript starter loaded")
}

on<PlayerJoinEvent> { event ->
    notify(event.player).success("Welcome, ${event.player.name}!")
}
