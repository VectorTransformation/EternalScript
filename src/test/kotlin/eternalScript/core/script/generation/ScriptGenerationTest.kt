package eternalScript.core.script.generation

import eternalScript.api.script.Script
import eternalScript.core.script.data.ScriptExecutionGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptGenerationTest {
    @Test
    fun `generation state transitions apply to every script`() {
        val generation = ScriptGeneration(
            listOf(
                object : Script() {},
                object : Script() {}
            ),
            TestGenerationRuntimeResource()
        )

        assertTrue(generation.publish())
        assertTrue(generation.scripts.all { script -> script.executionGate.isActive })
        assertEquals(ScriptExecutionGate.State.ACTIVE, generation.state)

        assertTrue(generation.tryFreeze())
        assertTrue(generation.scripts.all { script ->
            script.executionGate.state == ScriptExecutionGate.State.SWAPPING
        })
        assertTrue(generation.isDrained)

        assertTrue(generation.restore())
        assertTrue(generation.scripts.all { script -> script.executionGate.isActive })

        assertTrue(generation.retire())
        assertTrue(generation.scripts.all { script ->
            script.executionGate.state == ScriptExecutionGate.State.RETIRED
        })
        generation.dispose()
    }

    @Test
    fun `generation freeze blocks every child execution gate`() {
        val second = object : Script() {}
        val generation = ScriptGeneration(
            listOf(object : Script() {}, second),
            TestGenerationRuntimeResource()
        )

        assertTrue(generation.publish())
        assertEquals("second", second.executionGate.withActive { "second" })

        assertTrue(generation.tryFreeze())
        assertNull(second.executionGate.withActive { "blocked" })
        generation.retire()
        generation.dispose()
    }

    @Test
    fun `later enable failure deactivates attempted scripts in reverse order`() {
        val events = mutableListOf<String>()
        val generation = ScriptGeneration(
            listOf(
                LifecycleProbeScript("first", events),
                LifecycleProbeScript("second", events, failEnable = true),
                LifecycleProbeScript("never", events)
            ),
            TestGenerationRuntimeResource()
        )

        assertFailsWith<IllegalStateException> {
            generation.activate()
        }
        generation.retire()
        generation.deactivate()
        generation.dispose()

        assertEquals(
            listOf(
                "enable:first",
                "enable:second",
                "disable:second",
                "disable:first"
            ),
            events
        )
    }
}

private class TestGenerationRuntimeResource : GenerationRuntimeResource {
    override val pluginDependencies: Set<String> = emptySet()

    override fun close() = Unit
}

private class LifecycleProbeScript(
    private val name: String,
    private val events: MutableList<String>,
    failEnable: Boolean = false
) : Script() {
    init {
        onEnable {
            events += "enable:$name"
            if (failEnable) error("$name enable failed")
        }
        onDisable {
            events += "disable:$name"
        }
    }
}
