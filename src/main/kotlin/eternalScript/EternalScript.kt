package eternalScript

import eternalScript.core.command.MainCommand
import eternalScript.core.data.Config
import eternalScript.core.manager.ConfigManager
import eternalScript.core.manager.DataManager
import eternalScript.core.metrics.Metrics
import eternalScript.core.the.Root
import org.bukkit.plugin.java.JavaPlugin

class EternalScript : JavaPlugin() {
    override fun onEnable() {
        Root.register(ConfigManager)
        Root.register(DataManager)
        Root.register(MainCommand())
        if (ConfigManager.value(Config.METRICS)) {
            Metrics(this, 27192)
        }
    }
}