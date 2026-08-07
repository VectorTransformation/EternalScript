package eternalScript

import eternalScript.core.runtime.PluginRuntime
import org.bukkit.plugin.java.JavaPlugin

class EternalScript : JavaPlugin() {
    private var runtime: PluginRuntime? = null

    override fun onEnable() {
        check(runtime == null) { "EternalScript is already enabled." }
        runtime = PluginRuntime(this).also(PluginRuntime::start)
    }

    override fun onDisable() {
        runtime?.also { runtime = null }?.stop()
    }
}
