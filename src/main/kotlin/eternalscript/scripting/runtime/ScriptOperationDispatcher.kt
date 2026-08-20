package eternalscript.scripting.runtime

import eternalscript.api.ScriptOperation
import eternalscript.api.ScriptOperationResult
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal class ScriptOperationDispatcher(
    private val isMainThread: () -> Boolean,
    private val schedule: ((() -> Unit) -> Unit)
) {
    constructor(plugin: JavaPlugin) : this(
        Bukkit::isPrimaryThread,
        { block -> Bukkit.getScheduler().runTask(plugin, Runnable(block)) }
    )

    private val lock = Any()
    private val scheduled = linkedMapOf<CompletableFuture<ScriptOperationResult>, ScriptOperation>()

    @Volatile
    private var closed = false

    fun dispatch(
        operation: ScriptOperation,
        disabledResult: (ScriptOperation) -> ScriptOperationResult,
        block: (CompletableFuture<ScriptOperationResult>) -> Unit
    ): CompletionStage<ScriptOperationResult> {
        val future = CompletableFuture<ScriptOperationResult>()
        val execute = {
            if (closed) {
                future.complete(disabledResult(operation))
            } else {
                block(future)
            }
        }
        if (isMainThread()) {
            execute()
            return future
        }

        synchronized(lock) {
            if (closed) {
                future.complete(disabledResult(operation))
                return future
            }
            scheduled[future] = operation
            runCatching {
                schedule {
                    synchronized(lock) { scheduled.remove(future) }
                    execute()
                }
            }.onFailure {
                scheduled.remove(future)
                future.complete(disabledResult(operation))
            }
        }
        return future
    }

    fun close(disabledResult: (ScriptOperation) -> ScriptOperationResult) {
        synchronized(lock) {
            if (closed) return
            closed = true
            scheduled.forEach { (future, operation) ->
                future.complete(disabledResult(operation))
            }
            scheduled.clear()
        }
    }
}
