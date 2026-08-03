package eternalScript.core.operation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptOperationTest {
    @Test
    fun `operation reports accepted running and completed states`() {
        val tracker = ScriptOperationTracker(
            ScriptOperation(ScriptOperationKind.RELOAD)
        )

        assertEquals(ScriptOperationState.ACCEPTED, tracker.snapshot().state)

        tracker.start()
        assertEquals(ScriptOperationState.RUNNING, tracker.snapshot().state)

        tracker.complete(success = true)
        assertEquals(ScriptOperationState.COMPLETED, tracker.snapshot().state)
    }

    @Test
    fun `failure is terminal`() {
        val tracker = ScriptOperationTracker(
            ScriptOperation(ScriptOperationKind.CHECK)
        )

        tracker.start()
        tracker.fail()
        tracker.complete(success = true)
        tracker.cancel()

        assertEquals(ScriptOperationState.FAILED, tracker.snapshot().state)
    }

    @Test
    fun `cancellation is terminal`() {
        val tracker = ScriptOperationTracker(
            ScriptOperation(ScriptOperationKind.ENVIRONMENT_REFRESH)
        )

        tracker.start()
        tracker.cancel()
        tracker.complete(success = true)
        tracker.fail()

        assertEquals(ScriptOperationState.CANCELLED, tracker.snapshot().state)
    }

    @Test
    fun `background maintenance cannot replace the last user action`() {
        assertTrue(ScriptOperationKind.RELOAD.userVisible)
        assertTrue(ScriptOperationKind.CHECK.userVisible)
        assertTrue(ScriptOperationKind.UNLOAD.userVisible)
        assertFalse(ScriptOperationKind.ENVIRONMENT_REFRESH.userVisible)
    }
}
