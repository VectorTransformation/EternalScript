/**
 *
 * Hello, world!
 *
 */

enable {
    hello() // utils/util.kt
}

register<PlayerJoinEvent> { event ->
    val name = event.player.name
    Bukkit.broadcastMessage("Hello, $name!")
}