package eternalscript.feedback

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeedbackCatalogTest {
    private val bundledLocales = listOf("en_US", "ko_KR", "ja_JP", "zh_CN")

    @Test
    fun `bundled catalogs define the complete feedback contract`() {
        withExternalCatalogDirectory { directory ->
            val loaded = FeedbackCatalogLoader.load(bundledCatalogs(), directory)

            assertTrue(loaded.issues.isEmpty())
            assertEquals(setOf("en_us", "ja_jp", "ko_kr", "zh_cn"), loaded.catalogs.locales())
            FeedbackKey.entries.forEach { key ->
                bundledLocales.forEach { locale ->
                    loaded.catalogs.catalog(locale).template(key)
                }
            }
        }
    }

    @Test
    fun `normal feedback omits internal revisions and pipeline details`() {
        val removed = setOf(
            "command.operation.target",
            "command.operation.revision",
            "system.cache.retry",
            "system.pipeline.metrics"
        )

        assertTrue(FeedbackKey.entries.none { key -> key.id in removed })
        assertEquals(setOf("count", "page", "pages"), FeedbackKey.COMMAND_LIST_HEADER.placeholders)
        assertEquals(setOf("operation"), FeedbackKey.COMMAND_OPERATION_ACCEPTED.placeholders)
        assertEquals(setOf("count"), FeedbackKey.SYSTEM_STARTUP_SUCCESS.placeholders)
        bundledCatalogs().values.forEach { source ->
            removed.forEach { id -> assertTrue("\"$id\"" !in source) }
        }
    }

    @Test
    fun `Japanese and Chinese catalogs render localized values`() {
        withExternalCatalogDirectory { directory ->
            val loaded = FeedbackCatalogLoader.load(bundledCatalogs(), directory)
            val expected = mapOf(
                "ja-JP" to "[EternalScript] スクリプト対象が見つかりません: missing.eternal.kts",
                "zh-CN" to "[EternalScript] 找不到脚本目标：missing.eternal.kts"
            )

            expected.forEach { (locale, message) ->
                val renderer = FeedbackRenderer("EternalScript") { loaded.catalogs.catalog(locale) }
                assertEquals(
                    message,
                    plain(
                        renderer.render(
                            FeedbackView(
                                FeedbackLevel.INFO,
                                feedbackText(
                                    FeedbackKey.COMMAND_OPERATION_NOT_FOUND,
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
                  "_schema": 4,
                  "_locale": "ko_KR",
                  "messages": {
                    "command.operation.target": "대상"
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8
            )

            val loaded = FeedbackCatalogLoader.load(bundledCatalogs(), directory)

            assertEquals(2, loaded.issues.size)
            assertEquals(
                "No scripts are active",
                plain(
                    FeedbackRenderer("EternalScript") { loaded.catalogs.catalog("en_US") }
                        .render(
                            FeedbackView(
                                FeedbackLevel.INFO,
                                feedbackText(FeedbackKey.COMMAND_LIST_EMPTY)
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
                  "_schema": 4,
                  "_locale": "pirate",
                  "messages": {
                    "command.list.empty": "No scripts aboard"
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8
            )
            val loaded = FeedbackCatalogLoader.load(bundledCatalogs(), directory)
            val renderer = FeedbackRenderer("EternalScript") { loaded.catalogs.catalog("pirate") }

            assertEquals(
                "[EternalScript] No scripts aboard",
                plain(
                    renderer.render(
                        FeedbackView(FeedbackLevel.INFO, feedbackText(FeedbackKey.COMMAND_LIST_EMPTY))
                    ).single()
                )
            )
            loaded.catalogs.catalog("pirate").template(FeedbackKey.SYSTEM_STARTUP_SUCCESS)
        }
    }

    @Test
    fun `override messages must be JSON strings`() {
        withExternalCatalogDirectory { directory ->
            directory.resolve("fr_FR.json").writeText(
                """
                {
                  "_schema": 4,
                  "_locale": "fr_FR",
                  "messages": {
                    "command.list.empty": true
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8
            )

            val loaded = FeedbackCatalogLoader.load(bundledCatalogs(), directory)

            assertEquals(1, loaded.issues.size)
            assertTrue(loaded.issues.single().reason.contains("must be a string"))
            assertEquals(setOf("en_us", "ja_jp", "ko_kr", "zh_cn"), loaded.catalogs.locales())
        }
    }

    @Test
    fun `dynamic values remain literal and a multi-line view has one namespace`() {
        withExternalCatalogDirectory { directory ->
            val loaded = FeedbackCatalogLoader.load(bundledCatalogs(), directory)
            val renderer = FeedbackRenderer("EternalScript") { loaded.catalogs.catalog("en_US") }

            val lines = renderer.render(
                FeedbackView(
                    FeedbackLevel.ERROR,
                    feedbackText(
                        FeedbackKey.COMMAND_OPERATION_INVALID_PATH,
                        "target" to "<red>unsafe</red>"
                    ),
                    listOf(
                        FeedbackLine(
                            feedbackText(
                                FeedbackKey.COMMAND_ERROR_DETAIL,
                                "error" to "<click:run_command:'/stop'>literal</click>"
                            ),
                            FeedbackLineKind.ERROR
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
    fun `feedback values must exactly match the key contract`() {
        assertFailsWith<IllegalArgumentException> {
            feedbackText(FeedbackKey.COMMAND_OPERATION_NOT_FOUND)
        }
        assertFailsWith<IllegalArgumentException> {
            feedbackText(
                FeedbackKey.COMMAND_OPERATION_NOT_FOUND,
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
        val directory = Files.createTempDirectory("eternalscript-feedback-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
