package eternalscript.messaging

import eternalscript.api.script.notification.ScriptNotification
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageCatalogTest {
    private val bundledLocales = listOf("en_US", "ko_KR", "ja_JP", "zh_CN")

    @Test
    fun `bundled catalogs define the complete message contract`() {
        withExternalCatalogDirectory { directory ->
            val loaded = MessageCatalogLoader.load(bundledCatalogs(), directory)

            assertTrue(loaded.issues.isEmpty())
            assertEquals(setOf("en_us", "ja_jp", "ko_kr", "zh_cn"), loaded.catalogs.locales())
            MessageKey.entries.forEach { key ->
                bundledLocales.forEach { locale ->
                    loaded.catalogs.catalog(locale).template(key)
                }
            }
        }
    }

    @Test
    fun `script notifications render every level and preserve explicit styles`() {
        withExternalCatalogDirectory { directory ->
            val loaded = MessageCatalogLoader.load(bundledCatalogs(), directory)
            val renderer = MessageRenderer("EternalScript") { loaded.catalogs.catalog("en_US") }
            val expectedColors = mapOf(
                NotificationLevel.INFO to NamedTextColor.AQUA,
                NotificationLevel.SUCCESS to NamedTextColor.GREEN,
                NotificationLevel.WARN to NamedTextColor.YELLOW,
                NotificationLevel.ERROR to NamedTextColor.RED
            )

            expectedColors.forEach { (level, color) ->
                val lines = renderer.renderNotification(
                    level,
                    ScriptNotification("title", listOf("detail"), "hint")
                )
                assertEquals("[EternalScript] title", plain(lines[0]))
                assertTrue(plain(lines[1]).endsWith("detail"))
                assertTrue(plain(lines[2]).endsWith("hint"))
                assertEquals(color, lines[0].children().last().style().color())
                assertEquals(NamedTextColor.GRAY, lines[1].children().last().style().color())
                assertEquals(NamedTextColor.YELLOW, lines[2].children().last().style().color())
            }

            val styled = renderer.renderNotification(
                NotificationLevel.ERROR,
                ScriptNotification(Component.text("styled", NamedTextColor.LIGHT_PURPLE))
            ).single()
            assertEquals(NamedTextColor.LIGHT_PURPLE, styled.children().last().style().color())
        }
    }

    @Test
    fun `normal message omits internal revisions and pipeline details`() {
        val removed = setOf(
            "command.operation.target",
            "command.operation.revision",
            "system.cache.retry",
            "system.pipeline.metrics"
        )

        assertTrue(MessageKey.entries.none { key -> key.id in removed })
        assertEquals(setOf("filter", "count", "page", "pages"), MessageKey.COMMAND_LIST_HEADER.placeholders)
        assertEquals(setOf("operation"), MessageKey.COMMAND_OPERATION_ACCEPTED.placeholders)
        assertEquals(setOf("count"), MessageKey.SYSTEM_STARTUP_SUCCESS.placeholders)
        bundledCatalogs().values.forEach { source ->
            removed.forEach { id -> assertTrue("\"$id\"" !in source) }
        }
    }

    @Test
    fun `Japanese and Chinese catalogs render localized values`() {
        withExternalCatalogDirectory { directory ->
            val loaded = MessageCatalogLoader.load(bundledCatalogs(), directory)
            val expected = mapOf(
                "ja-JP" to "[EternalScript] スクリプト対象が見つかりません: missing.eternal.kts",
                "zh-CN" to "[EternalScript] 找不到脚本目标：missing.eternal.kts"
            )

            expected.forEach { (locale, message) ->
                val renderer = MessageRenderer("EternalScript") { loaded.catalogs.catalog(locale) }
                assertEquals(
                    message,
                    plain(
                        renderer.render(
                            MessageView(
                                MessageLevel.INFO,
                                messageText(
                                    MessageKey.COMMAND_OPERATION_NOT_FOUND,
                                    "target" to "missing.eternal.kts"
                                )
                            )
                        ).single()
                    )
                )
            }
        }
    }

    @Test
    fun `legacy and malformed overrides are ignored without replacing bundled catalogs`() {
        withExternalCatalogDirectory { directory ->
            directory.resolve("en_US.json").writeText(
                """{"command.list.empty":"legacy"}""",
                Charsets.UTF_8
            )
            directory.resolve("ko_KR.json").writeText(
                """
                {
                  "_schema": 5,
                  "_locale": "ko_KR",
                  "messages": {
                    "command.operation.target": "대상"
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8
            )

            val loaded = MessageCatalogLoader.load(bundledCatalogs(), directory)

            assertEquals(2, loaded.issues.size)
            assertEquals(
                "No script targets match active",
                plain(
                    MessageRenderer("EternalScript") { loaded.catalogs.catalog("en_US") }
                        .render(
                            MessageView(
                                MessageLevel.INFO,
                                messageText(MessageKey.COMMAND_LIST_EMPTY, "filter" to "active")
                            )
                        ).single()
                ).removePrefix("[EternalScript] ")
            )
        }
    }

    @Test
    fun `valid partial override inherits every other English message`() {
        withExternalCatalogDirectory { directory ->
            directory.resolve("pirate.json").writeText(
                """
                {
                  "_schema": 5,
                  "_locale": "pirate",
                  "messages": {
                    "command.list.empty": "No <filter> scripts aboard"
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8
            )
            val loaded = MessageCatalogLoader.load(bundledCatalogs(), directory)
            val renderer = MessageRenderer("EternalScript") { loaded.catalogs.catalog("pirate") }

            assertEquals(
                "[EternalScript] No active scripts aboard",
                plain(
                    renderer.render(
                        MessageView(
                            MessageLevel.INFO,
                            messageText(MessageKey.COMMAND_LIST_EMPTY, "filter" to "active")
                        )
                    ).single()
                )
            )
            loaded.catalogs.catalog("pirate").template(MessageKey.SYSTEM_STARTUP_SUCCESS)
        }
    }

    @Test
    fun `override messages must be JSON strings`() {
        withExternalCatalogDirectory { directory ->
            directory.resolve("fr_FR.json").writeText(
                """
                {
                  "_schema": 5,
                  "_locale": "fr_FR",
                  "messages": {
                    "command.list.empty": true
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8
            )

            val loaded = MessageCatalogLoader.load(bundledCatalogs(), directory)

            assertEquals(1, loaded.issues.size)
            assertTrue(loaded.issues.single().reason.contains("must be a string"))
            assertEquals(setOf("en_us", "ja_jp", "ko_kr", "zh_cn"), loaded.catalogs.locales())
        }
    }

    @Test
    fun `dynamic values remain literal and a multi-line view has one namespace`() {
        withExternalCatalogDirectory { directory ->
            val loaded = MessageCatalogLoader.load(bundledCatalogs(), directory)
            val renderer = MessageRenderer("EternalScript") { loaded.catalogs.catalog("en_US") }

            val lines = renderer.render(
                MessageView(
                    MessageLevel.ERROR,
                    messageText(
                        MessageKey.COMMAND_OPERATION_INVALID_PATH,
                        "target" to "<red>unsafe</red>"
                    ),
                    listOf(
                        MessageLine(
                            messageText(
                                MessageKey.COMMAND_ERROR_DETAIL,
                                "error" to "<click:run_command:'/stop'>literal</click>"
                            ),
                            MessageLineKind.ERROR
                        )
                    )
                )
            ).map(::plain)

            assertEquals(
                listOf(
                    "[EternalScript] Invalid script target: <red>unsafe</red>",
                    "  ! Cause: <click:run_command:'/stop'>literal</click>"
                ),
                lines
            )
        }
    }

    @Test
    fun `message values must exactly match the key contract`() {
        assertFailsWith<IllegalArgumentException> {
            messageText(MessageKey.COMMAND_OPERATION_NOT_FOUND)
        }
        assertFailsWith<IllegalArgumentException> {
            messageText(
                MessageKey.COMMAND_OPERATION_NOT_FOUND,
                "target" to "one",
                "target" to "two"
            )
        }
    }

    private fun bundledCatalogs(): Map<String, String> = bundledLocales.associateWith { locale ->
        checkNotNull(javaClass.classLoader.getResource("lang/$locale.json"))
            .readText(Charsets.UTF_8)
    }

    private fun plain(component: net.kyori.adventure.text.Component): String =
        PlainTextComponentSerializer.plainText().serialize(component)

    private fun withExternalCatalogDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("eternalscript-message-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
