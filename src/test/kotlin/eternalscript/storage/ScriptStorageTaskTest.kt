package eternalscript.storage

import eternalscript.scripting.runtime.ReplStateBridge
import eternalscript.scripting.runtime.ScriptExecutionContext
import eternalscript.api.script.Script
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptStorageTaskTest {
    @Test
    fun `task keeps generation leased and rebinds repl state after suspension`() {
        val disposed = AtomicBoolean()
        val context = ScriptExecutionContext(table("value", "expected")) { disposed.set(true) }
        val owner = ScriptTaskOwner().also { it.attachSource("state.eternal.kts") }
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val completed = CountDownLatch(1)
        try {
            context.executeOrNull {
                owner.launch(
                    dispatcher,
                    ScriptExecutionContext.acquireCurrent(),
                    { _, error -> throw AssertionError(error) }
                ) {
                    delay(10)
                    assertEquals("expected", ReplStateBridge.requireReady("value"))
                    completed.countDown()
                }
            }
            context.retire()
            assertFalse(disposed.get())
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            awaitCondition { disposed.get() }
        } finally {
            owner.close()
            context.retire()
            dispatcher.close()
        }
    }

    @Test
    fun `disposing owner cancels task and releases retired generation`() {
        val disposed = AtomicBoolean()
        val context = ScriptExecutionContext(table("value", "expected")) { disposed.set(true) }
        val owner = ScriptTaskOwner()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            context.executeOrNull {
                owner.launch(
                    dispatcher,
                    ScriptExecutionContext.acquireCurrent(),
                    { _, error -> throw AssertionError(error) }
                ) {
                    try {
                        started.countDown()
                        awaitCancellation()
                    } finally {
                        finished.countDown()
                    }
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            context.retire()
            assertFalse(disposed.get())

            owner.close()
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            awaitCondition { disposed.get() }
        } finally {
            owner.close()
            context.retire()
            dispatcher.close()
        }
    }

    @Test
    fun `uncaught task failure reports script source once`() {
        val context = ScriptExecutionContext(table("value", "expected")) {}
        val owner = ScriptTaskOwner().also { it.attachSource("failure.eternal.kts") }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val reported = CountDownLatch(1)
        val source = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        try {
            context.executeOrNull {
                owner.launch(
                    dispatcher,
                    ScriptExecutionContext.acquireCurrent(),
                    { path, error ->
                        source.set(path)
                        failure.set(error)
                        reported.countDown()
                    }
                ) {
                    error("storage failed")
                }
            }
            assertTrue(reported.await(10, TimeUnit.SECONDS))
            assertEquals("failure.eternal.kts", source.get())
            assertEquals("storage failed", failure.get()?.message)
        } finally {
            owner.close()
            context.retire()
            dispatcher.close()
        }
    }

    @Test
    fun `task lease cannot be acquired outside active callback`() {
        assertFailsWith<IllegalStateException> { ScriptExecutionContext.acquireCurrent() }
    }

    @Test
    fun `runtime storage rejects io outside storage task`() {
        val script = object : Script() {}
        val scope = script.storage("outside").global()
        val key = script.longKey("value")

        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { scope[key] }
        }
        assertTrue(failure.message.orEmpty().contains("inside storageTask"))
    }

    private fun table(key: String, value: Any): ReplStateBridge.StateTable =
        ReplStateBridge.StateTable(
            linkedMapOf(key to value),
            linkedSetOf(key)
        )

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition was not satisfied" }
            Thread.onSpinWait()
        }
    }
}
