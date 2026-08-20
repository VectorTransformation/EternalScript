package eternalscript.api

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EternalScriptApiContractTest {
    private val success = ScriptOperationResult(
        ScriptOperation.RELOAD,
        ScriptOperationStatus.SUCCESS,
        revision = 7,
        affectedPaths = listOf("a.eternal.kts")
    )

    private val api = object : EternalScriptApi {
        override fun snapshot() = ScriptSnapshot(
            revision = 7,
            state = ScriptEngineState.READY,
            busyOperation = null,
            scripts = listOf(ScriptInfo("a.eternal.kts"))
        )

        override fun reload() = completed(ScriptOperation.RELOAD)
        override fun recompile() = completed(ScriptOperation.RECOMPILE)
        override fun load(path: String) = completed(ScriptOperation.LOAD)
        override fun unload(path: String) = completed(ScriptOperation.UNLOAD)
        override fun clear() = completed(ScriptOperation.CLEAR)

        private fun completed(operation: ScriptOperation): CompletionStage<ScriptOperationResult> =
            CompletableFuture.completedFuture(success.copy(operation = operation))
    }

    @Test
    fun `exposes stable API version and nullable service lookup`() {
        assertEquals(1, EternalScriptApi.API_VERSION)
        assertNull(EternalScriptApi.getOrNull())
        assertFailsWith<IllegalStateException> { EternalScriptApi.get() }
    }

    @Test
    fun `suspend adapters preserve CompletionStage results`() = runBlocking {
        assertEquals(ScriptOperation.RELOAD, api.reloadAwait().operation)
        assertEquals(ScriptOperation.RECOMPILE, api.recompileAwait().operation)
        assertEquals(ScriptOperation.LOAD, api.loadAwait("a.eternal.kts").operation)
        assertEquals(ScriptOperation.UNLOAD, api.unloadAwait("a.eternal.kts").operation)
        assertEquals(ScriptOperation.CLEAR, api.clearAwait().operation)
    }

    @Test
    fun `cancelling a suspend wait does not cancel the shared operation`() = runBlocking {
        val shared = CompletableFuture<ScriptOperationResult>()
        val waitingApi = object : EternalScriptApi by api {
            override fun reload(): CompletionStage<ScriptOperationResult> = shared
        }
        val waiting = launch { waitingApi.reloadAwait() }
        yield()

        waiting.cancelAndJoin()

        assertFalse(shared.isCancelled)
        shared.complete(success)
        assertEquals(success, shared.get())
    }

    @Test
    fun `snapshot model contains sorted script paths`() {
        val snapshot = api.snapshot()
        assertEquals(ScriptEngineState.READY, snapshot.state)
        assertEquals(listOf("a.eternal.kts"), snapshot.scripts.map(ScriptInfo::path))
    }
}
