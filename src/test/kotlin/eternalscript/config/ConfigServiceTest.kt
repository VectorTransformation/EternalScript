package eternalscript.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.InvalidConfigurationException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigServiceTest {
    @Test
    fun `creates a config with only language and metrics`() {
        withTemporaryConfig { configFile ->
            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertEquals(PluginConfig(metricsEnabled = true, language = "en_US"), report.config)
            assertTrue(report.issues.isEmpty())
            val yaml = YamlConfiguration.loadConfiguration(configFile)
            assertEquals(setOf("language", "metrics"), yaml.getKeys(false))
            assertEquals("en_US", yaml.getString("language"))
            assertEquals(true, yaml.getBoolean("metrics"))
        }
    }

    @Test
    fun `accepts a loadable custom language catalog`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: pirate\nmetrics: false\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES + "pirate")

            assertEquals(PluginConfig(metricsEnabled = false, language = "pirate"), report.config)
            assertTrue(report.issues.isEmpty())
        }
    }

    @Test
    fun `migrates the legacy lang key without resetting the selected language`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("lang: ko_KR\nmetrics: false\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)
            val yaml = YamlConfiguration.loadConfiguration(configFile)

            assertEquals(PluginConfig(metricsEnabled = false, language = "ko_KR"), report.config)
            assertTrue(report.issues.isEmpty())
            assertEquals("ko_KR", yaml.getString("language"))
            assertTrue(!yaml.contains("lang"))
        }
    }

    @Test
    fun `removes a redundant legacy lang key when language already exists`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: ja_JP\nlang: ko_KR\nmetrics: true\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)
            val yaml = YamlConfiguration.loadConfiguration(configFile)

            assertEquals(PluginConfig(metricsEnabled = true, language = "ja_JP"), report.config)
            assertTrue(report.issues.isEmpty())
            assertEquals("ja_JP", yaml.getString("language"))
            assertTrue(!yaml.contains("lang"))
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
    fun `reports unknown top-level fields without discarding valid settings`() {
        withTemporaryConfig { configFile ->
            configFile.parentFile.mkdirs()
            configFile.writeText("language: ko_KR\nmetrics: false\nmatrics: true\n", Charsets.UTF_8)

            val report = ConfigService(configFile).reload(DEFAULT_LOCALES)

            assertEquals(PluginConfig(metricsEnabled = false, language = "ko_KR"), report.config)
            assertEquals(1, report.issues.size)
            assertTrue(report.issues.single().reason.contains("matrics"))
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

            assertEquals(PluginConfig(metricsEnabled = false, language = "ko_KR"), service.current)
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

            assertEquals(PluginConfig(metricsEnabled = false, language = "en_US"), report.config)
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
