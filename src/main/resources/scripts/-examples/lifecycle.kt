package eternalScript.examples

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit

class LifecycleExample : EternalScript() {
    override fun onEnable() {
        Bukkit.getServer().broadcast(Component.text("enable: script"))
    }

    override fun onDisable() {
        Bukkit.getServer().broadcast(Component.text("disable: script"))
    }
}
