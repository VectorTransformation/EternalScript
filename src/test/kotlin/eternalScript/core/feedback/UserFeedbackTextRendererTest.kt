package eternalScript.core.feedback

import eternalScript.core.feedback.UserFeedbackArgument.Quoted
import eternalScript.core.feedback.UserFeedbackArgument.Text
import eternalScript.core.feedback.UserFeedbackArgument.Translation
import kotlin.test.Test
import kotlin.test.assertEquals

class UserFeedbackTextRendererTest {
    @Test
    fun `dynamic text remains literal across formatting and line normalization`() {
        val renderer = UserFeedbackTextRenderer { key, _ ->
            check(key == "feedback.test.literal")
            "value=%s"
        }
        val value = "100% <tag> \"quoted\" C:\\plugins\\EternalScript\r\nnext"
        val message = UserFeedbackMessage(
            key = "feedback.test.literal",
            arguments = listOf(Text(value)),
            stage = UserFeedbackStage.DETAIL,
            severity = UserFeedbackSeverity.INFO
        )

        assertEquals(
            "value=100% <tag> \"quoted\" C:\\plugins\\EternalScript\\r\\nnext",
            renderer.render(message, "en_US")
        )
    }

    @Test
    fun `quoted arguments add only their presentation quotes`() {
        val renderer = UserFeedbackTextRenderer { _, _ -> "source=%s" }
        val message = UserFeedbackMessage(
            key = "feedback.test.quoted",
            arguments = listOf(Quoted("nested/source.kt")),
            stage = UserFeedbackStage.DETAIL,
            severity = UserFeedbackSeverity.INFO
        )

        assertEquals(
            "source=\"nested/source.kt\"",
            renderer.render(message, "en_US")
        )
    }

    @Test
    fun `translator fallback applies to both template and nested translation`() {
        val translations = mapOf(
            "en_US" to mapOf(
                "feedback.test.outer" to "Result: %s / %s",
                "feedback.test.inner" to "fallback detail"
            ),
            "ko_KR" to mapOf(
                "feedback.test.outer" to "결과: %s / %s"
            )
        )
        val renderer = UserFeedbackTextRenderer { key, language ->
            translations[language]?.get(key)
                ?: translations.getValue("en_US").getValue(key)
        }
        val message = UserFeedbackMessage(
            key = "feedback.test.outer",
            arguments = listOf(
                Translation("feedback.test.inner"),
                Text("100% <safe>")
            ),
            stage = UserFeedbackStage.RESULT,
            severity = UserFeedbackSeverity.SUCCESS
        )

        assertEquals(
            "결과: fallback detail / 100% <safe>",
            renderer.render(message, "ko_KR")
        )
        assertEquals(
            "Result: fallback detail / 100% <safe>",
            renderer.render(message, "unknown")
        )
    }
}
