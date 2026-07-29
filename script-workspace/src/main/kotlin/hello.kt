package eternalScript.workspace

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent

@EternalScriptEntry
internal fun Script.configureHello() {
    enable {
        Bukkit.broadcastMessage("Hello from the EternalScript workspace!")
    }

    event<PlayerJoinEvent> { event ->
        Bukkit.broadcastMessage(joinMessage(event.player.name))
    }
}
