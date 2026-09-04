package eternalscript.api.script.logging

/** Writes an operational message to the Paper server log for the current script. */
public interface ScriptLogger {
    public fun debug(message: String, cause: Throwable? = null)

    /** Builds [message] only when DEBUG logging is enabled. */
    public fun debug(message: () -> String)

    public fun info(message: String, cause: Throwable? = null)

    public fun warn(message: String, cause: Throwable? = null)

    public fun error(message: String, cause: Throwable? = null)
}
