package eternalScript

import eternalScript.core.command.MainCommand
import eternalScript.core.manager.DataManager
import eternalScript.core.manager.MetricsManager
import eternalScript.core.manager.ScriptManager
import eternalScript.core.the.Root
import org.bukkit.plugin.java.JavaPlugin

class EternalScript : JavaPlugin() {
    override fun onEnable() {
        Root.register(MainCommand)
        Root.register(DataManager)
        Root.register(MetricsManager)
    }

    override fun onDisable() {
        Root.unregister(ScriptManager)
    }
}