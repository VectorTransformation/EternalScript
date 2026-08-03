package eternalScript.core.script.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptRegistrationGateTest {
    @Test
    fun `registration scope is nested and closes after failure`() {
        val gate = ScriptRegistrationGate()

        assertFalse(gate.isOpen)
        runCatching {
            gate.withOpen {
                assertTrue(gate.isOpen)
                gate.withOpen {
                    assertTrue(gate.isOpen)
                }
                error("expected")
            }
        }
        assertFalse(gate.isOpen)
    }

    @Test
    fun `registration scope is not inherited by another thread`() {
        val gate = ScriptRegistrationGate()
        val observed = AtomicBoolean(true)

        gate.withOpen {
            val thread = Thread {
                observed.set(gate.isOpen)
            }
            thread.start()
            thread.join()

            assertTrue(gate.isOpen)
        }

        assertFalse(observed.get())
        assertFalse(gate.isOpen)
    }
}
