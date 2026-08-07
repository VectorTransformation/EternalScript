package eternalScript.examples

import eternalScript.api.script.EternalScript
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class EventExample : EternalScript() {
    override fun onEnable() {
        events {
            on<PlayerJoinEvent> { event ->
                Bukkit.getServer().broadcast(joinMessage(event.player.name))
            }

            on<PlayerQuitEvent> { event ->
                Bukkit.getServer().broadcast(quitMessage(event.player.name))
            }
        }
    }
}
