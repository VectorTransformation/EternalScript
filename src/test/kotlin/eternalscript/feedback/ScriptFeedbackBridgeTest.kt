package eternalscript.feedback

import eternalscript.api.script.Script
import eternalscript.api.script.feedback.ScriptFeedbackLevel
import eternalscript.api.script.feedback.ScriptFeedbackMessage
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptFeedbackBridgeTest {
    @Test
    fun `script feedback reaches the installed service with structured content`() {
        var captured: Triple<Audience, ScriptFeedbackLevel, ScriptFeedbackMessage>? = null
        val registration = ScriptFeedbackBridge.install { audience, level, message ->
            captured = Triple(audience, level, message)
        }
        try {
            val audience = Audience.empty()
            val script = object : Script() {}

            script.feedback(
                audience,
                ScriptFeedbackMessage("Saved", listOf("profile.json"), "You can reload it now"),
                ScriptFeedbackLevel.SUCCESS
            )

            assertEquals(audience, captured?.first)
            assertEquals(ScriptFeedbackLevel.SUCCESS, captured?.second)
            assertEquals(
                "Saved",
                captured?.third?.title?.let(PlainTextComponentSerializer.plainText()::serialize)
            )
            assertEquals(1, captured?.third?.details?.size)
        } finally {
            registration.close()
        }
    }

    @Test
    fun `script feedback fails clearly outside the plugin lifecycle`() {
        val script = object : Script() {}

        val failure = assertFailsWith<IllegalStateException> {
            script.feedback(Audience.empty(), "Unavailable")
        }

        assertEquals(
            "EternalScript feedback is unavailable because the plugin is not active",
            failure.message
        )
    }
}
