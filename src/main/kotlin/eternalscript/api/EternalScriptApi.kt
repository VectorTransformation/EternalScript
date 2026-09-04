package eternalscript.api

import org.bukkit.Bukkit
import java.util.concurrent.CompletionStage

public interface EternalScriptApi {
    public fun snapshot(): ScriptSnapshot

    /**
     * Rereads every enabled source from the scripts directory and replaces the complete generation.
     * Use this operation to apply edits made on disk.
     */
    public fun reload(): CompletionStage<ScriptOperationResult>

    public fun reload(path: String): CompletionStage<ScriptOperationResult>

    /** Compiles enabled disk sources without changing the active generation. */
    public fun check(): CompletionStage<ScriptOperationResult>

    public fun check(path: String): CompletionStage<ScriptOperationResult>

    /**
     * Recompiles the source texts captured by the current in-memory generation without rereading disk.
     */
    public fun recompile(): CompletionStage<ScriptOperationResult>

    /**
     * Loads or replaces an EternalScript file, or every enabled descendant of a directory target.
     * An already active target is replaced even when its source text has not changed.
     * A single leading `-` on the final path segment is treated as the persistent disabled marker.
     */
    public fun enable(path: String): CompletionStage<ScriptOperationResult>

    /**
     * Unloads an EternalScript file or directory target and persists that state with a leading `-`.
     * The operation fails without changing state when an active consumer outside the target depends on it.
     */
    public fun disable(path: String): CompletionStage<ScriptOperationResult>

    /**
     * Cancels the current preparing or compiling operation without changing the active generation.
     * Returns `BUSY` once generation application or lifecycle cleanup has started.
     */
    public fun cancel(): CompletionStage<ScriptOperationResult>

    public companion object {
        public const val API_VERSION: Int = 2

        @JvmStatic
        public fun get(): EternalScriptApi = getOrNull()
            ?: throw IllegalStateException(
                "EternalScript API is unavailable. Ensure EternalScript is enabled and declared as a Paper dependency."
            )

        @JvmStatic
        public fun getOrNull(): EternalScriptApi? = runCatching {
            Bukkit.getServicesManager().load(EternalScriptApi::class.java)
        }.getOrNull()
    }
}
