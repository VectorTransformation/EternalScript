package eternalScript.examples

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit

class CommandExample : EternalScript() {
    override fun onEnable() {
        commands {
            command("test-command") {
                aliases("t1", "t2")
                permission(null)
                suggests { emptyList() }
                executes {
                    Bukkit.getServer().broadcast(
                        Component.text(
                            "sender: ${sender.name}, arguments: $arguments"
                        )
                    )
                }
            }
        }
    }
}
