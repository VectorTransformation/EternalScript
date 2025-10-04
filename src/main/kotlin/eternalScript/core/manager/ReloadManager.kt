package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.api.manager.Reloader
import org.bukkit.command.CommandSender

object ReloadManager : Manager, Reloader {
    override fun register() {
        reload()
    }

    override fun reload(sender: CommandSender?, silent: Boolean) {
        ConfigManager.reload(sender, silent)
        LangManager.reload(sender, silent)
    }
}