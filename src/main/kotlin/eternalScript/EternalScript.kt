package eternalScript

import eternalScript.command.MainCommand
import eternalScript.manager.ConfigManager
import eternalScript.manager.DataManager
import eternalScript.the.Root
import org.bukkit.plugin.java.JavaPlugin

class EternalScript : JavaPlugin() {
    override fun onEnable() {
        ConfigManager.all()
        DataManager.all()
        Root.registers(MainCommand())
    }
}