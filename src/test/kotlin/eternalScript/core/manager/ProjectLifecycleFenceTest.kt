package eternalScript.core.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectLifecycleFenceTest {
    @Test
    fun `expired session cannot mutate after project lifecycle reopens`() {
        val lifecycle = ProjectLifecycleFence()
        val oldSession = lifecycle.open()

        lifecycle.close()
        val newSession = lifecycle.open()

        assertTrue(newSession > oldSession)
        assertFalse(lifecycle.accepts(oldSession))
        assertTrue(lifecycle.accepts(newSession))
    }

    @Test
    fun `closed project lifecycle exposes no operation session`() {
        val lifecycle = ProjectLifecycleFence()

        assertNull(lifecycle.openSession())
        val session = lifecycle.open()
        assertEquals(session, lifecycle.openSession())
        lifecycle.close()
        assertNull(lifecycle.openSession())
    }
}
