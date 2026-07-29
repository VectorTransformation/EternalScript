package eternalScript.core.script.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptDataLifecycleTest {
    @Test
    fun `deactivation is exactly once and a later activation starts a new cycle`() {
        var enables = 0
        var disables = 0
        val lifecycle = ScriptLifecycleState()

        lifecycle.activate { enables += 1 }
        lifecycle.activate { enables += 1 }
        lifecycle.deactivate { disables += 1 }
        lifecycle.deactivate { disables += 1 }

        assertEquals(1, enables)
        assertEquals(1, disables)

        lifecycle.activate { enables += 1 }
        lifecycle.deactivate { disables += 1 }

        assertEquals(2, enables)
        assertEquals(2, disables)
    }

    @Test
    fun `failed activation owns one matching deactivation`() {
        var disables = 0
        val lifecycle = ScriptLifecycleState()

        assertFailsWith<IllegalStateException> {
            lifecycle.activate { error("enable failed") }
        }
        lifecycle.deactivate { disables += 1 }
        lifecycle.deactivate { disables += 1 }

        assertEquals(1, disables)
    }

    @Test
    fun `unregister and late operation cleanup dispose exactly once`() {
        var disposals = 0
        val disposal = ScriptDisposalState()

        disposal.dispose { disposals += 1 }
        disposal.dispose { disposals += 1 }

        assertEquals(1, disposals)
    }

    @Test
    fun `failed disposal is not rerun by a late cleanup owner`() {
        var attempts = 0
        val disposal = ScriptDisposalState()

        assertFailsWith<IllegalStateException> {
            disposal.dispose {
                attempts += 1
                error("dispose failed")
            }
        }
        disposal.dispose { attempts += 1 }

        assertEquals(1, attempts)
    }
}
