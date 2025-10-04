package eternalScript.core.manager

import eternalScript.api.manager.Reloader
import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.the.Root
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.command.CommandSender

object LangManager : Reloader {
    private val cache = mutableMapOf<String, JsonObject>()

    override fun reload(sender: CommandSender?, silent: Boolean) {
        cache.clear()
        Resource.LANG.searchSequence { file ->
            file.extension == "json"
        }.forEach { file ->
            val lang = file.nameWithoutExtension.lowercase()
            cache[lang] = Json.decodeFromString(file.readText())
        }
    }

    fun translatable(key: String, lang: String = ConfigManager.value(Config.LANG)) = cache[lang.lowercase()]?.get(key)?.jsonPrimitive?.contentOrNull ?: key

    fun sendMessage(sender: CommandSender?, key: String, lang: String = ConfigManager.value(Config.LANG), args: List<String> = emptyList()) {
        val message = translatable(key, lang).format(*args.toTypedArray())
        Root.sendInfo(sender, message)
    }
}