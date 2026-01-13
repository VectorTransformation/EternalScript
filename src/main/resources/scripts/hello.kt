/**
 *
 * Hello, world!
 *
 */

enable {
    Bukkit.broadcastMessage(message) // merge: utils/util.kt
}

register<PlayerJoinEvent> { event ->
    val name = event.player.name
    Bukkit.broadcastMessage("Hello, $name!")
}