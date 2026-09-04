package eternalscript.config

import eternalscript.logging.EternalLogLevel
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.InvalidConfigurationException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigServiceTest {
    @Test
    fun `creates a config with logging defaults`() {
        withTemporaryConfig { configFile ->
            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertEquals(
                PluginConfig(true, true, "en_US", EternalLogLevel.INFO, 500L),
                report.config
            )
            assertTrue(report.issues.isEmpty())
            val yaml = YamlConfiguration.loadConfiguration(configFile)
            assertEquals(
                setOf("language", "metrics", "cache", "logging-level", "slow-storage-ms"),
                yaml.getKeys(false)
            )
            assertEquals("en_US", yaml.getString("language"))
            assertEquals(true, yaml.getBoolean("metrics"))
            assertEquals(true, yaml.getBoolean("cache"))
            assertEquals("INFO", yaml.getString("logging-level"))
            assertEquals(500L, yaml.getLong("slow-storage-ms"))
        }
    }

    @Test
    fun `accepts a loadable custom language catalog`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: pirate\nmetrics: false\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES + "pirate")

            assertEquals(
                PluginConfig(false, true, "pirate", EternalLogLevel.INFO, 500L),
                report.config
            )
            assertTrue(report.issues.isEmpty())
        }
    }

    @Test
    fun `accepts disabled cache`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: en_US\nmetrics: true\ncache: false\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertFalse(report.config.cacheEnabled)
            assertTrue(report.issues.isEmpty())
        }
    }

    @Test
    fun `invalid cache value falls back and reports an issue`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: en_US\nmetrics: true\ncache: no please\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertTrue(report.config.cacheEnabled)
            assertEquals(1, report.issues.size)
            assertTrue(report.issues.single().reason.contains("cache must be a boolean"))
        }
    }

    @Test
    fun `accepts case insensitive logging level and disabled slow storage warning`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText(
                "language: en_US\nmetrics: true\nlogging-level: debug\nslow-storage-ms: 0\n",
                Charsets.UTF_8
            )

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertEquals(EternalLogLevel.DEBUG, report.config.loggingLevel)
            assertEquals(0L, report.config.slowStorageMillis)
            assertTrue(report.issues.isEmpty())
        }
    }

    @Test
    fun `invalid logging settings fall back and report both issues`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText(
                "language: en_US\nmetrics: true\nlogging-level: verbose\nslow-storage-ms: -1\n",
                Charsets.UTF_8
            )

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertEquals(EternalLogLevel.INFO, report.config.loggingLevel)
            assertEquals(500L, report.config.slowStorageMillis)
            assertEquals(2, report.issues.size)
            assertTrue(report.issues.any { issue -> "logging-level" in issue.reason })
            assertTrue(report.issues.any { issue -> "slow-storage-ms" in issue.reason })
        }
    }

    @Test
    fun `slow storage threshold rejects decimal numbers`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText(
                "language: en_US\nmetrics: true\nlogging-level: INFO\nslow-storage-ms: 1.5\n",
                Charsets.UTF_8
            )

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertEquals(500L, report.config.slowStorageMillis)
            assertEquals(1, report.issues.size)
            assertTrue("slow-storage-ms" in report.issues.single().reason)
        }
    }

    @Test
    fun `falls back and reports a language without a loadable catalog`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: missing_LOCALE\nmetrics: true\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertEquals("en_US", report.config.language)
            assertEquals(1, report.issues.size)
            assertEquals("config.yml", report.issues.single().file)
            assertTrue(report.issues.single().reason.contains("missing_LOCALE"))
            assertTrue(report.issues.single().reason.contains("using en_US"))
        }
    }

    @Test
    fun `falls back and reports a non-string language`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: 42\nmetrics: true\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertEquals("en_US", report.config.language)
            assertEquals(1, report.issues.size)
            assertTrue(report.issues.single().reason.contains("non-blank string"))
        }
    }

    @Test
    fun `falls back and reports a non-boolean metrics value`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: en_US\nmetrics: yes please\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertEquals(true, report.config.metricsEnabled)
            assertEquals(1, report.issues.size)
            assertTrue(report.issues.single().reason.contains("metrics must be a boolean"))
            assertTrue(report.issues.single().reason.contains("using true"))
        }
    }

    @Test
    fun `reports an old field without migrating or discarding valid settings`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: ja_JP\nlang: ko_KR\nmetrics: false\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)
            val yaml = YamlConfiguration.loadConfiguration(configFile)

            assertEquals(
                PluginConfig(false, true, "ja_JP", EternalLogLevel.INFO, 500L),
                report.config
            )
            assertEquals(1, report.issues.size)
            assertTrue(report.issues.single().reason.contains("lang"))
            assertEquals("ko_KR", yaml.getString("lang"))
            assertEquals("ja_JP", yaml.getString("language"))
        }
    }

    @Test
    fun `malformed yaml preserves the active config and original file`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: ko_KR\nmetrics: false\n", Charsets.UTF_8)
            val service = ConfigService(configFile)
            service.reload(DEFAULT_LOCALES)
            val malformed = "language: [broken\nmetrics: true\n"
            configFile.writeText(malformed, Charsets.UTF_8)

            assertFailsWith<InvalidConfigurationException> {
                service.reload(DEFAULT_LOCALES)
            }

            assertEquals(
                PluginConfig(false, true, "ko_KR", EternalLogLevel.INFO, 500L),
                service.current
            )
            assertEquals(malformed, configFile.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun `restores absent supported keys without replacing existing values`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("metrics: false\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)
            val yaml = YamlConfiguration.loadConfiguration(configFile)

            assertEquals(
                PluginConfig(false, true, "en_US", EternalLogLevel.INFO, 500L),
                report.config
            )
            assertTrue(report.issues.isEmpty())
            assertEquals("en_US", yaml.getString("language"))
            assertEquals(false, yaml.getBoolean("metrics"))
        }
    }

    private fun withTemporaryConfig(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("eternalscript-config-test").toFile()
        try {
            block(directory.resolve("config.yml"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        val DEFAULT_LOCALES: Set<String> = setOf("en_us", "ko_kr", "ja_jp", "zh_cn")
    }
}
