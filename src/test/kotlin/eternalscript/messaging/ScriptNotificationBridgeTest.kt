package eternalscript.messaging

import eternalscript.api.script.Script
import eternalscript.api.script.notification.ScriptNotification
import eternalscript.api.script.notification.ScriptNotifier
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScriptNotificationBridgeTest {
    @Test
    fun `public notifier exposes three message shapes for every level`() {
        val methods = ScriptNotifier::class.java.declaredMethods
            .groupingBy { method -> method.name }
            .eachCount()

        assertEquals(
            mapOf("info" to 3, "success" to 3, "warn" to 3, "error" to 3),
            methods
        )
    }

    @Test
    fun `script notifier audience defaults to the Paper console`() {
        val audience = Script::class.members
            .single { function -> function.name == "notify" }
            .parameters
            .single { parameter -> parameter.name == "audience" }

        assertTrue(audience.isOptional)
    }

    @Test
    fun `scoped notifier sends every level and message shape to the selected audience`() {
        val captured = mutableListOf<Captured>()
        val registration = ScriptNotificationBridge.install { audience, level, message ->
            captured += Captured(audience, level, message)
        }
        try {
            val audience = Audience.empty()
            val notifier = object : Script() {}.notify(audience)

            notifier.info("Info")
            notifier.success(Component.text("Success"))
            notifier.warn(ScriptNotification("Warning", listOf("detail"), "hint"))
            notifier.error("Error")

            assertEquals(NotificationLevel.entries, captured.map(Captured::level))
            captured.forEach { entry -> assertSame(audience, entry.audience) }
            assertEquals(
                listOf("Info", "Success", "Warning", "Error"),
                captured.map { entry -> plain(entry.message.title) }
            )
            assertEquals(1, captured[2].message.details.size)
            assertEquals("hint", captured[2].message.hint?.let(::plain))
        } finally {
            registration.close()
        }
    }

    @Test
    fun `notifier fails clearly when messaging lifecycle is inactive`() {
        val notifier = object : Script() {}.notify(Audience.empty())

        val failure = assertFailsWith<IllegalStateException> {
            notifier.error("Unavailable")
        }

        assertEquals(
            "EternalScript messaging is unavailable because the plugin is not active",
            failure.message
        )
    }

    private fun plain(component: Component): String =
        PlainTextComponentSerializer.plainText().serialize(component)

    private data class Captured(
        val audience: Audience,
        val level: NotificationLevel,
        val message: ScriptNotification
    )
}
