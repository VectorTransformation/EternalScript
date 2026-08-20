package eternalscript.feedback

import eternalscript.api.script.feedback.ScriptFeedbackLevel
import eternalscript.api.script.feedback.ScriptFeedbackMessage
import net.kyori.adventure.audience.Audience
import java.util.concurrent.atomic.AtomicReference

internal fun interface ScriptFeedbackSink {
    fun send(audience: Audience, level: ScriptFeedbackLevel, message: ScriptFeedbackMessage)
}

internal object ScriptFeedbackBridge {
    private data class Registration(
        val token: Any,
        val sink: ScriptFeedbackSink
    )

    private val registration = AtomicReference<Registration?>()

    fun install(sink: ScriptFeedbackSink): AutoCloseable {
        val installed = Registration(Any(), sink)
        check(registration.compareAndSet(null, installed)) {
            "A script feedback service is already installed"
        }
        return AutoCloseable { registration.compareAndSet(installed, null) }
    }

    fun send(audience: Audience, level: ScriptFeedbackLevel, message: ScriptFeedbackMessage) {
        val sink = registration.get()?.sink
            ?: error("EternalScript feedback is unavailable because the plugin is not active")
        sink.send(audience, level, message)
    }
}
