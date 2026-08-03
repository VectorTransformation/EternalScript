package eternalScript.core.feedback

import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.manager.ConfigManager
import eternalScript.core.the.Root
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Loads, upgrades, and resolves localized user-feedback templates. */
internal object LocaleCatalog {
    private const val FALLBACK_LANGUAGE = "en_us"
    private const val SCHEMA_KEY = "_schema"
    val bundledLanguages = listOf("en_US", "ko_KR", "ja_JP", "zh_CN")
    private val json = Json { prettyPrint = true }
    private val cache = mutableMapOf<String, JsonObject>()

    fun reload() {
        cache.clear()
        bundledLanguages.forEach { language ->
            Root.INSTANCE.getResource("lang/$language.json")
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader ->
                    cache[language.lowercase()] = json.decodeFromString(reader.readText())
                }
        }
        Resource.LANG.searchSequence { file -> file.extension == "json" }
            .forEach { file ->
                val language = file.nameWithoutExtension.lowercase()
                val installed = json.decodeFromString<JsonObject>(file.readText())
                val bundled = cache[language]
                val current = resolveInstalledCatalog(installed, bundled)
                if (current != installed) {
                    file.writeText(json.encodeToString(current))
                }
                cache[language] = current
            }
    }

    internal fun resolveInstalledCatalog(
        installed: JsonObject,
        bundled: JsonObject?
    ): JsonObject {
        val installedSchema = installed[SCHEMA_KEY]?.jsonPrimitive?.intOrNull ?: 0
        val bundledSchema = bundled?.get(SCHEMA_KEY)?.jsonPrimitive?.intOrNull ?: 0
        return if (installedSchema < bundledSchema && bundled != null) {
            bundled
        } else {
            JsonObject(bundled.orEmpty() + installed)
        }
    }

    fun translate(
        key: String,
        language: String = ConfigManager.value(Config.LANG)
    ): String =
        (cache[language.lowercase()]?.get(key) ?: cache[FALLBACK_LANGUAGE]?.get(key))
            ?.jsonPrimitive
            ?.contentOrNull
            ?: key
}
