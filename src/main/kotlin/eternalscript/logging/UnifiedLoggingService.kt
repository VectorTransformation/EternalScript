package eternalscript.logging

import eternalscript.api.script.logging.ScriptLogger
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.plugin.java.JavaPlugin

internal enum class EternalLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR;

    fun allows(level: EternalLogLevel): Boolean = level.ordinal >= ordinal

    companion object {
        fun parse(value: String): EternalLogLevel? = entries.firstOrNull { level ->
            level.name == value.trim().uppercase(Locale.ROOT)
        }
    }
}

internal interface OperationalLogSink {
    fun write(level: EternalLogLevel, message: Component, cause: Throwable?)
}

internal class UnifiedLoggingService(
    private val level: () -> EternalLogLevel,
    private val slowStorageMillis: () -> Long,
    private val sink: OperationalLogSink
) {
    constructor(
        plugin: JavaPlugin,
        level: () -> EternalLogLevel,
        slowStorageMillis: () -> Long
    ) : this(
        level = level,
        slowStorageMillis = slowStorageMillis,
        sink = PaperOperationalLogSink(plugin.componentLogger)
    )

    fun logger(): AttachedScriptLogger = AttachedScriptLogger(this)

    fun system(level: EternalLogLevel, message: Component, cause: Throwable?) {
        write(level, message, cause)
    }

    fun storageTaskFailure(source: String, error: Throwable) {
        script(source, EternalLogLevel.ERROR, "Persistent storage task failed", error)
    }

    fun slowStorageOperation(
        source: String,
        namespace: String,
        scopeType: String,
        operation: String,
        key: String?,
        elapsedMillis: Long
    ) {
        val threshold = slowStorageMillis()
        if (threshold == 0L || elapsedMillis < threshold) return
        val details = buildString {
            append("Slow persistent storage operation: namespace=")
            append(namespace)
            append(" scope=")
            append(scopeType)
            append(" operation=")
            append(operation)
            if (key != null) {
                append(" key=")
                append(key)
            }
            append(" elapsedMs=")
            append(elapsedMillis)
        }
        script(source, EternalLogLevel.WARN, details, null)
    }

    fun isEnabled(level: EternalLogLevel): Boolean = this.level().allows(level)

    fun script(source: String, level: EternalLogLevel, message: String, cause: Throwable?) {
        val prefix = if (level == EternalLogLevel.DEBUG) {
            "[script:$source] [DEBUG] "
        } else {
            "[script:$source] "
        }
        write(level, Component.text(prefix + message), cause)
    }

    private fun write(level: EternalLogLevel, message: Component, cause: Throwable?) {
        if (isEnabled(level)) sink.write(level, message, cause)
    }
}

internal class AttachedScriptLogger(
    private val service: UnifiedLoggingService
) : ScriptLogger {
    private val source = AtomicReference("<script>")

    fun attachSource(path: String) {
        val previous = source.get()
        check(previous == "<script>" || previous == path) {
            "Script logger is already attached to $previous"
        }
        source.set(path)
    }

    override fun debug(message: String, cause: Throwable?) {
        service.script(source.get(), EternalLogLevel.DEBUG, message, cause)
    }

    override fun debug(message: () -> String) {
        if (service.isEnabled(EternalLogLevel.DEBUG)) debug(message())
    }

    override fun info(message: String, cause: Throwable?) {
        service.script(source.get(), EternalLogLevel.INFO, message, cause)
    }

    override fun warn(message: String, cause: Throwable?) {
        service.script(source.get(), EternalLogLevel.WARN, message, cause)
    }

    override fun error(message: String, cause: Throwable?) {
        service.script(source.get(), EternalLogLevel.ERROR, message, cause)
    }
}

internal object ScriptLoggingRuntime {
    private val current = AtomicReference<UnifiedLoggingService?>()

    fun install(service: UnifiedLoggingService): AutoCloseable {
        check(current.compareAndSet(null, service)) { "A script logging runtime is already installed" }
        return AutoCloseable { current.compareAndSet(service, null) }
    }

    fun logger(): RuntimeScriptLogger = RuntimeScriptLogger()

    fun service(): UnifiedLoggingService = checkNotNull(current.get()) {
        "Script logging is unavailable because EternalScript is not active"
    }
}

internal class RuntimeScriptLogger : ScriptLogger {
    private val source = AtomicReference("<script>")

    fun attachSource(path: String) {
        val previous = source.get()
        check(previous == "<script>" || previous == path) {
            "Script logger is already attached to $previous"
        }
        source.set(path)
    }

    override fun debug(message: String, cause: Throwable?) {
        ScriptLoggingRuntime.service().script(source.get(), EternalLogLevel.DEBUG, message, cause)
    }

    override fun debug(message: () -> String) {
        val service = ScriptLoggingRuntime.service()
        if (service.isEnabled(EternalLogLevel.DEBUG)) {
            service.script(source.get(), EternalLogLevel.DEBUG, message(), null)
        }
    }

    override fun info(message: String, cause: Throwable?) {
        ScriptLoggingRuntime.service().script(source.get(), EternalLogLevel.INFO, message, cause)
    }

    override fun warn(message: String, cause: Throwable?) {
        ScriptLoggingRuntime.service().script(source.get(), EternalLogLevel.WARN, message, cause)
    }

    override fun error(message: String, cause: Throwable?) {
        ScriptLoggingRuntime.service().script(source.get(), EternalLogLevel.ERROR, message, cause)
    }
}

private class PaperOperationalLogSink(
    private val logger: ComponentLogger
) : OperationalLogSink {
    override fun write(level: EternalLogLevel, message: Component, cause: Throwable?) {
        when (level) {
            EternalLogLevel.DEBUG,
            EternalLogLevel.INFO -> if (cause == null) logger.info(message) else logger.info(message, cause)
            EternalLogLevel.WARN -> if (cause == null) logger.warn(message) else logger.warn(message, cause)
            EternalLogLevel.ERROR -> if (cause == null) logger.error(message) else logger.error(message, cause)
        }
    }
}
