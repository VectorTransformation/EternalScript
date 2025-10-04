/**
 *
 * Hello, world!
 *
 */

enable {
    hello() // utils/util.kt
}

event<PlayerJoinEvent> { event ->
    Bukkit.broadcastMessage("Hello, ${event.player.name}!")
}