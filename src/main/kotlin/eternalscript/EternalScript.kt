package eternalscript

import eternalscript.bootstrap.PluginRuntime
import org.bukkit.plugin.java.JavaPlugin

public class EternalScript : JavaPlugin() {
    private var runtime: PluginRuntime? = null

    override fun onEnable() {
        runtime = PluginRuntime(this).also(PluginRuntime::enable)
    }

    override fun onDisable() {
        runtime?.close()
        runtime = null
    }
}
