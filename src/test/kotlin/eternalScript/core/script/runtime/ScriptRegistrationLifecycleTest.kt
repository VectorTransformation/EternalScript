package eternalScript.core.script.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptRegistrationLifecycleTest {
    @Test
    fun `activation definitions are replaced instead of accumulated on rollback`() {
        val lifecycle = ScriptRegistrationLifecycle<String>()
        lifecycle.add("constructor", registrationGateOpen = false)

        lifecycle.beginActivation()
        lifecycle.add("first-cycle", registrationGateOpen = true)
        assertEquals(
            listOf("constructor", "first-cycle"),
            lifecycle.activate()
        )
        lifecycle.deactivate()

        lifecycle.beginActivation()
        lifecycle.add("second-cycle", registrationGateOpen = true)

        assertEquals(
            listOf("constructor", "second-cycle"),
            lifecycle.activate()
        )
    }

    @Test
    fun `activation registration is rejected outside lifecycle thread`() {
        val lifecycle = ScriptRegistrationLifecycle<String>()
        lifecycle.beginActivation()

        assertFailsWith<IllegalStateException> {
            lifecycle.add("background-coroutine", registrationGateOpen = false)
        }
        assertEquals(emptyList(), lifecycle.snapshot())
    }
}
