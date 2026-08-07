package eternalScript.examples

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent

class HelloExample : EternalScript() {
    override fun onEnable() {
        Bukkit.getServer().broadcast(Component.text("Hello, World!"))

        events {
            on<PlayerJoinEvent> { event ->
                Bukkit.getServer().broadcast(
                    Component.text("Hello, ${event.player.name}!")
                )
            }
        }
    }
}
