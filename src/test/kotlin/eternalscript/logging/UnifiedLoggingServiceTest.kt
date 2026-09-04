package eternalscript.logging

import eternalscript.api.script.Script
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UnifiedLoggingServiceTest {
    @Test
    fun `script logger filters levels and does not build disabled debug message`() {
        var configuredLevel = EternalLogLevel.INFO
        val sink = RecordingSink()
        val service = service({ configuredLevel }, { 500L }, sink)
        val logger = service.logger().also { it.attachSource("acb/player.eternal.kts") }
        var built = false

        logger.debug {
            built = true
            "hidden"
        }
        logger.info("loaded")

        assertFalse(built)
        assertEquals(listOf(EternalLogLevel.INFO), sink.entries.map(Entry::level))
        assertEquals("[script:acb/player.eternal.kts] loaded", sink.entries.single().text)

        configuredLevel = EternalLogLevel.DEBUG
        logger.debug { "profile=ready" }

        assertEquals(EternalLogLevel.DEBUG, sink.entries.last().level)
        assertEquals(
            "[script:acb/player.eternal.kts] [DEBUG] profile=ready",
            sink.entries.last().text
        )
    }

    @Test
    fun `script logger preserves causes and system messages use logging levels`() {
        val sink = RecordingSink()
        val service = service({ EternalLogLevel.DEBUG }, { 500L }, sink)
        val logger = service.logger().also { it.attachSource("failure.eternal.kts") }
        val failure = IllegalStateException("broken")

        logger.error("save failed", failure)
        service.system(EternalLogLevel.INFO, Component.text("ready"), null)

        assertSame(failure, sink.entries.first().cause)
        assertEquals(EternalLogLevel.ERROR, sink.entries.first().level)
        assertEquals("[script:failure.eternal.kts] save failed", sink.entries.first().text)
        assertEquals(EternalLogLevel.INFO, sink.entries.last().level)
    }

    @Test
    fun `slow storage warning honors live threshold and omits values and scope ids`() {
        var threshold = 500L
        val sink = RecordingSink()
        val service = service({ EternalLogLevel.DEBUG }, { threshold }, sink)

        service.slowStorageOperation(
            "storage.eternal.kts",
            "example.profiles",
            "player",
            "get",
            "coins",
            499L
        )
        assertTrue(sink.entries.isEmpty())

        service.slowStorageOperation(
            "storage.eternal.kts",
            "example.profiles",
            "player",
            "get",
            "coins",
            500L
        )
        val warning = sink.entries.single()
        assertEquals(EternalLogLevel.WARN, warning.level)
        assertTrue("namespace=example.profiles" in warning.text)
        assertTrue("scope=player" in warning.text)
        assertTrue("operation=get" in warning.text)
        assertTrue("key=coins" in warning.text)
        assertTrue("elapsedMs=500" in warning.text)
        assertFalse("scope_id" in warning.text)
        assertFalse("value=" in warning.text)

        threshold = 0L
        service.slowStorageOperation(
            "storage.eternal.kts",
            "example.profiles",
            "player",
            "get",
            "coins",
            Long.MAX_VALUE
        )
        assertEquals(1, sink.entries.size)
    }

    @Test
    fun `runtime logger keeps attached source and becomes unavailable after shutdown`() {
        val sink = RecordingSink()
        val service = service({ EternalLogLevel.INFO }, { 500L }, sink)
        val registration = ScriptLoggingRuntime.install(service)
        val script = object : Script() {}
        try {
            script.attachRuntimeSource("lifecycle.eternal.kts")
            script.log.info("top level")

            assertEquals("[script:lifecycle.eternal.kts] top level", sink.entries.single().text)
        } finally {
            registration.close()
        }

        assertFailsWith<IllegalStateException> { script.log.info("late") }
    }

    private fun service(
        level: () -> EternalLogLevel,
        threshold: () -> Long,
        sink: RecordingSink
    ): UnifiedLoggingService = UnifiedLoggingService(level, threshold, sink)

    private class RecordingSink : OperationalLogSink {
        val entries = mutableListOf<Entry>()

        override fun write(level: EternalLogLevel, message: Component, cause: Throwable?) {
            entries += Entry(level, (message as TextComponent).content(), cause)
        }
    }

    private data class Entry(
        val level: EternalLogLevel,
        val text: String,
        val cause: Throwable?
    )
}
