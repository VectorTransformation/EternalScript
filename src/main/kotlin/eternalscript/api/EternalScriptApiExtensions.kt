package eternalscript.api

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletionStage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public suspend fun EternalScriptApi.reloadAwait(): ScriptOperationResult = reload().awaitResult()

public suspend fun EternalScriptApi.recompileAwait(): ScriptOperationResult = recompile().awaitResult()

public suspend fun EternalScriptApi.loadAwait(path: String): ScriptOperationResult = load(path).awaitResult()

public suspend fun EternalScriptApi.unloadAwait(path: String): ScriptOperationResult = unload(path).awaitResult()

public suspend fun EternalScriptApi.clearAwait(): ScriptOperationResult = clear().awaitResult()

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
