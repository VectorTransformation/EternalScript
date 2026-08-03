package eternalScript

import eternalScript.core.command.MainCommand
import eternalScript.core.manager.DataManager
import eternalScript.core.manager.MetricsManager
import eternalScript.core.manager.ScriptManager
import eternalScript.core.the.Root
import org.bukkit.plugin.java.JavaPlugin

class EternalScript : JavaPlugin() {
    override fun onEnable() {
        Root.startup()
        Root.register(MainCommand)
        Root.start(DataManager)
        Root.start(MetricsManager)
    }

    override fun onDisable() {
        Root.stop(DataManager, ScriptManager)
    }
}
