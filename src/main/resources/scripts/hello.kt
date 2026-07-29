/**
 *
 * Hello, world!
 *
 */

package eternalScript.examples

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent

@EternalScriptEntry
internal fun Script.configureHello() {
    enable {
        Bukkit.broadcastMessage("Hello, World!")
    }

    register<PlayerJoinEvent> { event ->
        val name = event.player.name
        Bukkit.broadcastMessage("Hello, $name!")
    }
}
