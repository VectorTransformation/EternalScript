package eternalScript.core.manager

import eternalScript.api.manager.Reloader
import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.the.Root
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.command.CommandSender

object LangManager : Reloader {
    private const val FALLBACK_LANG = "en_us"
    private const val LANG_SCHEMA_KEY = "_schema"
    private val bundledLanguages = listOf("en_US", "ko_KR")
    private val obsoleteKeys = setOf(
        "config.reload",
        "script.format",
        "script.diagnostic",
        "script.list",
        "script.load.completed",
        "script.loaded",
        "script.not_found",
        "script.reload.dependents_completed",
        "script.reload.dependents_started",
        "script.unload.all",
        "script.unloaded",
        "script.wait"
    )
    private val json = Json {
        prettyPrint = true
    }
    private val cache = mutableMapOf<String, JsonObject>()

    override fun reload(sender: CommandSender?, silent: Boolean) {
        cache.clear()
        bundledLanguages.forEach { lang ->
            Root.INSTANCE.getResource("lang/$lang.json")?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                cache[lang.lowercase()] = json.decodeFromString(reader.readText())
            }
        }
        Resource.LANG.searchSequence { file ->
            file.extension == "json"
        }.forEach { file ->
            val lang = file.nameWithoutExtension.lowercase()
            val installed = json.decodeFromString<JsonObject>(file.readText())
            val current = installed.filterKeys { it !in obsoleteKeys }
            val bundled = cache[lang]
            val installedSchema = installed[LANG_SCHEMA_KEY]?.jsonPrimitive?.intOrNull ?: 0
            val bundledSchema = bundled?.get(LANG_SCHEMA_KEY)?.jsonPrimitive?.intOrNull ?: 0
            val custom = current.filterKeys { bundled == null || it !in bundled }
            val merged = if (installedSchema < bundledSchema) {
                JsonObject(bundled.orEmpty() + custom)
            } else {
                JsonObject(bundled.orEmpty() + current)
            }
            if (merged != installed) {
                file.writeText(json.encodeToString(merged))
            }
            cache[lang] = merged
        }
    }

    fun translatable(key: String, lang: String = ConfigManager.value(Config.LANG)) =
        (cache[lang.lowercase()]?.get(key) ?: cache[FALLBACK_LANG]?.get(key))
            ?.jsonPrimitive
            ?.contentOrNull
            ?: key

    fun sendMessage(sender: CommandSender?, key: String, lang: String = ConfigManager.value(Config.LANG), args: List<String> = emptyList()) {
        val message = translatable(key, lang).format(*args.toTypedArray())
        Root.sendInfo(sender, message)
    }
}
