package eternalScript.core.script.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptExecutionGateTest {
    @Test
    fun `staged and retired gates reject execution`() {
        val gate = ScriptExecutionGate()

        assertNull(gate.withActive { "staged" })
        assertEquals(ScriptExecutionGate.State.STAGED, gate.state)
        assertTrue(gate.retire())
        assertNull(gate.withActive { "retired" })
        assertFalse(gate.publish())
        assertFalse(gate.restore())
    }

    @Test
    fun `published gate admits and releases readers`() {
        val gate = ScriptExecutionGate()

        assertTrue(gate.publish())
        assertEquals("active", gate.withActive { "active" })
        assertEquals(0, gate.readerCount)
        assertTrue(gate.isDrained)
        assertTrue(gate.isActive)
    }

    @Test
    fun `freeze rejects new readers while admitted reader drains`() {
        val gate = ScriptExecutionGate()
        assertTrue(gate.publish())

        gate.withActive {
            assertEquals(1, gate.readerCount)
            assertTrue(gate.tryFreeze())
            assertFalse(gate.isDrained)
            assertNull(gate.withActive { "nested" })
            assertEquals(1, gate.readerCount)
        }

        assertTrue(gate.isDrained)
        assertEquals(ScriptExecutionGate.State.SWAPPING, gate.state)
        assertNull(gate.withActive { "swapping" })
        assertTrue(gate.restore())
        assertEquals("restored", gate.withActive { "restored" })
    }

    @Test
    fun `freeze can roll back before admitted reader exits`() {
        val gate = ScriptExecutionGate()
        assertTrue(gate.publish())

        gate.withActive {
            assertTrue(gate.tryFreeze())
            assertFalse(gate.isDrained)
            assertTrue(gate.restore())
            assertTrue(gate.isActive)
            assertEquals("nested", gate.withActive { "nested" })
        }

        assertTrue(gate.isDrained)
    }

    @Test
    fun `reader is released when callback fails`() {
        val gate = ScriptExecutionGate()
        assertTrue(gate.publish())

        assertFailsWith<IllegalStateException> {
            gate.withActive {
                error("failure")
            }
        }

        assertEquals(0, gate.readerCount)
        assertTrue(gate.tryFreeze())
    }
}
