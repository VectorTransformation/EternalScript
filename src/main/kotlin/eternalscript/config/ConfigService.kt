package eternalscript.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Locale

internal data class PluginConfig(
    val metricsEnabled: Boolean,
    val language: String
)

internal data class ConfigReloadIssue(
    val file: String,
    val reason: String
)

internal data class ConfigReloadReport(
    val config: PluginConfig,
    val issues: List<ConfigReloadIssue>
)

internal class ConfigService(
    private val configFile: File
) {
    @Volatile
    var current: PluginConfig = DEFAULT_CONFIG
        private set

    fun reload(availableLocales: Set<String>): ConfigReloadReport {
        val normalizedLocales = availableLocales.mapTo(linkedSetOf(), ::normalizeConfiguredLocale)
        check(normalizeConfiguredLocale(DEFAULT_CONFIG.language) in normalizedLocales) {
            "The default language ${DEFAULT_CONFIG.language} is not available"
        }

        configFile.parentFile?.mkdirs()
        if (!configFile.exists()) {
            val defaults = YamlConfiguration()
            defaults.set(LANGUAGE_KEY, DEFAULT_CONFIG.language)
            defaults.set(METRICS_KEY, DEFAULT_CONFIG.metricsEnabled)
            defaults.save(configFile)
        }

        val yaml = YamlConfiguration().also { configuration -> configuration.load(configFile) }
        var changed = false
        if (yaml.contains(LEGACY_LANGUAGE_KEY)) {
            if (!yaml.contains(LANGUAGE_KEY)) {
                yaml.set(LANGUAGE_KEY, yaml.get(LEGACY_LANGUAGE_KEY))
            }
            yaml.set(LEGACY_LANGUAGE_KEY, null)
            changed = true
        }
        if (!yaml.contains(LANGUAGE_KEY)) {
            yaml.set(LANGUAGE_KEY, DEFAULT_CONFIG.language)
            changed = true
        }
        if (!yaml.contains(METRICS_KEY)) {
            yaml.set(METRICS_KEY, DEFAULT_CONFIG.metricsEnabled)
            changed = true
        }
        if (changed) yaml.save(configFile)

        val issues = mutableListOf<ConfigReloadIssue>()
        val unknownKeys = yaml.getKeys(false) - SUPPORTED_KEYS
        if (unknownKeys.isNotEmpty()) {
            issues += ConfigReloadIssue(
                configFile.name,
                "Unknown configuration field(s): ${unknownKeys.sorted().joinToString()}"
            )
        }
        val configuredLanguage = (yaml.get(LANGUAGE_KEY) as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val language = when {
            configuredLanguage == null -> {
                issues += ConfigReloadIssue(
                    configFile.name,
                    "language must be a non-blank string; using ${DEFAULT_CONFIG.language}"
                )
                DEFAULT_CONFIG.language
            }
            normalizeConfiguredLocale(configuredLanguage) !in normalizedLocales -> {
                issues += ConfigReloadIssue(
                    configFile.name,
                    "Configured language '$configuredLanguage' has no loadable catalog; " +
                        "using ${DEFAULT_CONFIG.language}"
                )
                DEFAULT_CONFIG.language
            }
            else -> configuredLanguage
        }
        val configuredMetrics = yaml.get(METRICS_KEY) as? Boolean
        val metricsEnabled = configuredMetrics ?: run {
            issues += ConfigReloadIssue(
                configFile.name,
                "metrics must be a boolean; using ${DEFAULT_CONFIG.metricsEnabled}"
            )
            DEFAULT_CONFIG.metricsEnabled
        }
        val loaded = PluginConfig(
            metricsEnabled = metricsEnabled,
            language = language
        )
        current = loaded
        return ConfigReloadReport(loaded, issues.toList())
    }

    private companion object {
        const val LANGUAGE_KEY: String = "language"
        const val LEGACY_LANGUAGE_KEY: String = "lang"
        const val METRICS_KEY: String = "metrics"
        val SUPPORTED_KEYS: Set<String> = setOf(LANGUAGE_KEY, METRICS_KEY)
        val DEFAULT_CONFIG: PluginConfig = PluginConfig(metricsEnabled = true, language = "en_US")
    }
}

private fun normalizeConfiguredLocale(locale: String): String =
    locale.trim().replace('-', '_').lowercase(Locale.ROOT)
