package eternalScript.workspace

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent

class WorkspaceHello : EternalScript() {
    override fun onEnable() {
        Bukkit.getServer().broadcast(
            Component.text("Hello from the EternalScript workspace!")
        )

        event<PlayerJoinEvent> { event ->
            Bukkit.getServer().broadcast(
                Component.text(joinMessage(event.player.name))
            )
        }
    }
}
