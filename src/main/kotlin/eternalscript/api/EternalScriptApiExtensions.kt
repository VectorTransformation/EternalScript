package eternalscript.api

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletionStage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public suspend fun EternalScriptApi.reloadAwait(path: String? = null): ScriptOperationResult =
    (if (path == null) reload() else reload(path)).awaitResult()

public suspend fun EternalScriptApi.checkAwait(path: String? = null): ScriptOperationResult =
    (if (path == null) check() else check(path)).awaitResult()

public suspend fun EternalScriptApi.recompileAwait(): ScriptOperationResult = recompile().awaitResult()

public suspend fun EternalScriptApi.enableAwait(path: String): ScriptOperationResult = enable(path).awaitResult()

public suspend fun EternalScriptApi.disableAwait(path: String): ScriptOperationResult = disable(path).awaitResult()

public suspend fun EternalScriptApi.cancelAwait(): ScriptOperationResult = cancel().awaitResult()

private suspend fun <T> CompletionStage<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (!continuation.isActive) return@whenComplete
        if (error == null) {
            continuation.resume(value)
        } else {
            continuation.resumeWithException(error)
        }
    }
}
