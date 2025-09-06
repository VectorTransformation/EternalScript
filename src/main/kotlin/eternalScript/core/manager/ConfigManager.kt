package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.the.Root
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

object ConfigManager : Manager {
    private val cache = mutableMapOf<String, Any>()

    override fun register() {
        reload()
    }

    fun reload(sender: CommandSender? = null, silent: Boolean = true) {
        load(sender, silent)
    }

    private fun load(sender: CommandSender? = null, silent: Boolean = true) {
        val file = Resource.CONFIG.make()
        val map = Config.entries.associate {
            it.key to Pair(it.value, it.comment)
        }.toSortedMap()
        val yml = YamlConfiguration.loadConfiguration(file)
        val keys = yml.getKeys(true)
        val filter = map.filter {
            it.key !in keys
        }
        if (filter.isNotEmpty()) {
            set(filter, yml, file)
        }
        map.forEach { entry ->
            yml.get(entry.key)?.let {
                cache[entry.key] = it
            }
        }
        if (!silent) {
            val result = "Config Reloaded"
            Root.sendInfo(sender, result)
        }
    }

    private fun set(map: Map<String, Pair<Any, List<String>>>, yml: YamlConfiguration, file: File) {
        map.forEach {
            val value = it.value.first
            yml.set(it.key, value)

            val comment = it.value.second
            if (comment.isNotEmpty()) {
                yml.setComments(it.key, comment)
            }
        }
        yml.save(file)
    }

    fun <T> value(config: Config): T {
        return cache[config.key] as? T ?: config.value as T
    }
}