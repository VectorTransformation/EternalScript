package eternalscript.config

import eternalscript.logging.EternalLogLevel
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Locale

internal data class PluginConfig(
    val metricsEnabled: Boolean,
    val cacheEnabled: Boolean,
    val language: String,
    val loggingLevel: EternalLogLevel,
    val slowStorageMillis: Long
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
            defaults.set(CACHE_KEY, DEFAULT_CONFIG.cacheEnabled)
            defaults.set(LOGGING_LEVEL_KEY, DEFAULT_CONFIG.loggingLevel.name)
            defaults.set(SLOW_STORAGE_MILLIS_KEY, DEFAULT_CONFIG.slowStorageMillis)
            defaults.save(configFile)
        }

        val yaml = YamlConfiguration().also { configuration -> configuration.load(configFile) }
        var changed = false
        if (!yaml.contains(LANGUAGE_KEY)) {
            yaml.set(LANGUAGE_KEY, DEFAULT_CONFIG.language)
            changed = true
        }
        if (!yaml.contains(METRICS_KEY)) {
            yaml.set(METRICS_KEY, DEFAULT_CONFIG.metricsEnabled)
            changed = true
        }
        if (!yaml.contains(CACHE_KEY)) {
            yaml.set(CACHE_KEY, DEFAULT_CONFIG.cacheEnabled)
            changed = true
        }
        if (!yaml.contains(LOGGING_LEVEL_KEY)) {
            yaml.set(LOGGING_LEVEL_KEY, DEFAULT_CONFIG.loggingLevel.name)
            changed = true
        }
        if (!yaml.contains(SLOW_STORAGE_MILLIS_KEY)) {
            yaml.set(SLOW_STORAGE_MILLIS_KEY, DEFAULT_CONFIG.slowStorageMillis)
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
        val configuredCache = yaml.get(CACHE_KEY) as? Boolean
        val cacheEnabled = configuredCache ?: run {
            issues += ConfigReloadIssue(
                configFile.name,
                "cache must be a boolean; using ${DEFAULT_CONFIG.cacheEnabled}"
            )
            DEFAULT_CONFIG.cacheEnabled
        }
        val configuredLoggingLevel = (yaml.get(LOGGING_LEVEL_KEY) as? String)
            ?.let(EternalLogLevel::parse)
        val loggingLevel = configuredLoggingLevel ?: run {
            issues += ConfigReloadIssue(
                configFile.name,
                "logging-level must be one of DEBUG, INFO, WARN, ERROR; using ${DEFAULT_CONFIG.loggingLevel}"
            )
            DEFAULT_CONFIG.loggingLevel
        }
        val configuredSlowStorageMillis = (yaml.get(SLOW_STORAGE_MILLIS_KEY) as? Number)
            ?.let { value ->
                when (value) {
                    is Byte,
                    is Short,
                    is Int,
                    is Long -> value.toLong().takeIf { longValue -> longValue >= 0L }
                    else -> null
                }
            }
        val slowStorageMillis = configuredSlowStorageMillis ?: run {
            issues += ConfigReloadIssue(
                configFile.name,
                "slow-storage-ms must be a non-negative integer; using ${DEFAULT_CONFIG.slowStorageMillis}"
            )
            DEFAULT_CONFIG.slowStorageMillis
        }
        val loaded = PluginConfig(
            metricsEnabled = metricsEnabled,
            cacheEnabled = cacheEnabled,
            language = language,
            loggingLevel = loggingLevel,
            slowStorageMillis = slowStorageMillis
        )
        current = loaded
        return ConfigReloadReport(loaded, issues.toList())
    }

    private companion object {
        const val LANGUAGE_KEY: String = "language"
        const val METRICS_KEY: String = "metrics"
        const val CACHE_KEY: String = "cache"
        const val LOGGING_LEVEL_KEY: String = "logging-level"
        const val SLOW_STORAGE_MILLIS_KEY: String = "slow-storage-ms"
        val SUPPORTED_KEYS: Set<String> = setOf(
            LANGUAGE_KEY,
            METRICS_KEY,
            CACHE_KEY,
            LOGGING_LEVEL_KEY,
            SLOW_STORAGE_MILLIS_KEY
        )
        val DEFAULT_CONFIG: PluginConfig = PluginConfig(
            metricsEnabled = true,
            cacheEnabled = true,
            language = "en_US",
            loggingLevel = EternalLogLevel.INFO,
            slowStorageMillis = 500L
        )
    }
}

private fun normalizeConfiguredLocale(locale: String): String =
    locale.trim().replace('-', '_').lowercase(Locale.ROOT)
