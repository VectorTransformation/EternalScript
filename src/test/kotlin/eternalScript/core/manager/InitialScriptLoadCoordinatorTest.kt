package eternalScript.core.manager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitialScriptLoadCoordinatorTest {
    @Test
    fun `server load initializes once while explicit server reload initializes again`() {
        val coordinator = InitialScriptLoadCoordinator()

        assertTrue(coordinator.onServerLoad(reload = false))
        assertFalse(coordinator.onServerLoad(reload = false))
        assertTrue(coordinator.onServerLoad(reload = true))
        assertTrue(coordinator.serverLoaded)
    }

    @Test
    fun `one tick fallback and later server event do not duplicate initial load`() {
        val coordinator = InitialScriptLoadCoordinator()

        assertTrue(coordinator.onFallback(sessionOpen = true))
        assertFalse(coordinator.onFallback(sessionOpen = true))
        assertFalse(coordinator.onServerLoad(reload = false))
    }

    @Test
    fun `closed session does not consume fallback and reset permits next startup`() {
        val coordinator = InitialScriptLoadCoordinator()

        assertFalse(coordinator.onFallback(sessionOpen = false))
        assertFalse(coordinator.serverLoaded)
        assertTrue(coordinator.onFallback(sessionOpen = true))

        coordinator.reset()

        assertFalse(coordinator.serverLoaded)
        assertTrue(coordinator.onServerLoad(reload = false))
    }
}
