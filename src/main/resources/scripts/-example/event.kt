/**
 *
 * event
 *
 */

package eternalScript.examples

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

@EternalScriptEntry
internal fun Script.configureEventExample() {
    register<PlayerJoinEvent> { event ->
        val name = event.player.name
        Bukkit.broadcastMessage("join: $name")
    }

    register<PlayerQuitEvent> { event ->
        val name = event.player.name
        Bukkit.broadcastMessage("quit: $name")
    }
}
