package eternalscript.scripting.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptExecutionContextTest {
    @Test
    fun `nested callbacks use their own generation and restore the caller generation`() {
        val outer = ScriptExecutionContext(table("value", "outer")) {}
        val inner = ScriptExecutionContext(table("value", "inner")) {}
        try {
            outer.executeOrNull {
                assertEquals("outer", ReplStateBridge.requireReady("value"))
                inner.executeOrNull {
                    assertEquals("inner", ReplStateBridge.requireReady("value"))
                }
                assertEquals("outer", ReplStateBridge.requireReady("value"))
            }
        } finally {
            outer.retire()
            inner.retire()
        }
    }

    @Test
    fun `an active callback does not read candidate staging state`() {
        val active = ScriptExecutionContext(table("value", "active")) {}
        try {
            ReplStateBridge.stage(table("value", "candidate")) {
                assertEquals("candidate", ReplStateBridge.requireReady("value"))
                active.executeOrNull {
                    assertEquals("active", ReplStateBridge.requireReady("value"))
                }
                assertEquals("candidate", ReplStateBridge.requireReady("value"))
            }
        } finally {
            active.retire()
        }
    }

    @Test
    fun `an in flight callback keeps its original state until it exits`() {
        val oldDisposed = AtomicBoolean()
        val newDisposed = AtomicBoolean()
        val oldState = table("value", "old")
        val newState = table("value", "new")
        val oldContext = ScriptExecutionContext(oldState) { oldDisposed.set(true) }
        val newContext = ScriptExecutionContext(newState) { newDisposed.set(true) }
        val handle = ScriptExecutionHandle(oldContext)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val activeBefore = ReplStateBridge.publish(newState)
        try {
            val running = executor.submit<String> {
                handle.execute {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    ReplStateBridge.requireReady("value") as String
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            handle.update(newContext)
            oldContext.retire()
            assertFalse(oldDisposed.get())
            assertEquals("new", handle.execute { ReplStateBridge.requireReady("value") })

            release.countDown()
            assertEquals("old", running.get(10, TimeUnit.SECONDS))
            assertTrue(oldDisposed.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            oldContext.retire()
            newContext.retire()
            ReplStateBridge.publish(activeBefore)
        }
        assertTrue(newDisposed.get())
    }

    private fun table(key: String, value: Any): ReplStateBridge.StateTable =
        ReplStateBridge.StateTable(
            linkedMapOf(key to value),
            linkedSetOf(key)
        )
}
