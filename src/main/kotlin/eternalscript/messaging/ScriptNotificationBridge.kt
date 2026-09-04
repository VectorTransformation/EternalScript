package eternalscript.messaging

import eternalscript.api.script.notification.ScriptNotification
import eternalscript.api.script.notification.ScriptNotifier
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component

internal enum class NotificationLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR
}

internal fun interface ScriptNotificationSink {
    fun send(audience: Audience, level: NotificationLevel, message: ScriptNotification)
}

internal object ScriptNotificationBridge {
    private data class Registration(
        val token: Any,
        val sink: ScriptNotificationSink
    )

    private val registration = AtomicReference<Registration?>()

    fun install(sink: ScriptNotificationSink): AutoCloseable {
        val installed = Registration(Any(), sink)
        check(registration.compareAndSet(null, installed)) {
            "A script notification service is already installed"
        }
        return AutoCloseable { registration.compareAndSet(installed, null) }
    }

    fun notifier(audience: Audience): ScriptNotifier = ScopedScriptNotifier(audience)

    private fun send(audience: Audience, level: NotificationLevel, message: ScriptNotification) {
        val sink = registration.get()?.sink
            ?: error("EternalScript messaging is unavailable because the plugin is not active")
        sink.send(audience, level, message)
    }

    private class ScopedScriptNotifier(
        private val audience: Audience
    ) : ScriptNotifier {
        override fun info(message: String) = info(ScriptNotification(message))
        override fun info(message: Component) = info(ScriptNotification(message))
        override fun info(message: ScriptNotification) = send(NotificationLevel.INFO, message)

        override fun success(message: String) = success(ScriptNotification(message))
        override fun success(message: Component) = success(ScriptNotification(message))
        override fun success(message: ScriptNotification) = send(NotificationLevel.SUCCESS, message)

        override fun warn(message: String) = warn(ScriptNotification(message))
        override fun warn(message: Component) = warn(ScriptNotification(message))
        override fun warn(message: ScriptNotification) = send(NotificationLevel.WARN, message)

        override fun error(message: String) = error(ScriptNotification(message))
        override fun error(message: Component) = error(ScriptNotification(message))
        override fun error(message: ScriptNotification) = send(NotificationLevel.ERROR, message)

        private fun send(level: NotificationLevel, message: ScriptNotification) {
            ScriptNotificationBridge.send(audience, level, message)
        }
    }
}
