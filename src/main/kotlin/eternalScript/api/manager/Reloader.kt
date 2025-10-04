package eternalScript.api.manager

import org.bukkit.command.CommandSender

interface Reloader {
    fun reload(sender: CommandSender? = null, silent: Boolean = true)
}