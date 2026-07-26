/**
 *
 * Hello, world!
 *
 */

enable {
    Bukkit.broadcastMessage("Hello, World!")
}

register<PlayerJoinEvent> { event ->
    val name = event.player.name
    Bukkit.broadcastMessage("Hello, $name!")
}