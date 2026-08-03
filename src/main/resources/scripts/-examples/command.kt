package eternalScript.examples

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit

class CommandExample : EternalScript() {
    override fun onEnable() {
        command("test-command") {
            aliases("t1", "t2")
            permission(null)
            tabCompleter { _, _, _ -> emptyList() }
            executor { sender, _, _ ->
                Bukkit.getServer().broadcast(
                    Component.text("sender: ${sender.name}")
                )
            }
        }
    }
}
