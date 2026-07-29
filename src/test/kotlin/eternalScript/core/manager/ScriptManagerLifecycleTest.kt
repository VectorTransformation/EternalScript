package eternalScript.core.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptManagerLifecycleTest {
    @Test
    fun `close and reopen cannot accept a previous lifecycle epoch`() {
        val lifecycle = ScriptManagerLifecycle()

        val first = lifecycle.open()
        assertEquals(first, lifecycle.openEpoch())
        assertTrue(lifecycle.accepts(first))

        lifecycle.close()
        assertNull(lifecycle.openEpoch())
        assertFalse(lifecycle.accepts(first))

        val second = lifecycle.open()
        assertTrue(second > first)
        assertTrue(lifecycle.accepts(second))
        assertFalse(lifecycle.accepts(first))
    }

    @Test
    fun `repeated open or close does not create an artificial epoch`() {
        val lifecycle = ScriptManagerLifecycle()

        val opened = lifecycle.open()
        assertEquals(opened, lifecycle.open())
        val closed = lifecycle.close()
        assertEquals(closed, lifecycle.close())
    }

    @Test
    fun `normal retirement claim prevents unregister from claiming twice`() {
        val registry = GenerationOwnershipRegistry<Any>()
        val generation = Any()

        assertTrue(registry.transfer(generation))
        assertTrue(registry.claim(generation))
        assertTrue(registry.claimAll().isEmpty())
        assertFalse(registry.claim(generation))
    }

    @Test
    fun `unregister claim prevents a late operation retirement`() {
        val registry = GenerationOwnershipRegistry<Any>()
        val generation = Any()

        assertTrue(registry.transfer(generation))
        assertEquals(listOf(generation), registry.claimAll())
        assertFalse(registry.claim(generation))
    }
}
