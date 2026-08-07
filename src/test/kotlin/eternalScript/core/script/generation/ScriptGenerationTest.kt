package eternalScript.core.script.generation

import eternalScript.api.script.EternalScript
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.runtime.ManagedScriptRuntime
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
                managed(object : EternalScript() {}),
                managed(object : EternalScript() {})
            ),
            TestGenerationRuntimeResource()
        )

        assertTrue(generation.publish())
        assertTrue(generation.instances.all { runtime -> runtime.executionGate.isActive })
        assertEquals(ScriptExecutionGate.State.ACTIVE, generation.state)

        assertTrue(generation.tryFreeze())
        assertTrue(generation.instances.all { runtime ->
            runtime.executionGate.state == ScriptExecutionGate.State.SWAPPING
        })
        assertTrue(generation.isDrained)

        assertTrue(generation.restore())
        assertTrue(generation.instances.all { runtime -> runtime.executionGate.isActive })

        assertTrue(generation.retire())
        assertTrue(generation.instances.all { runtime ->
            runtime.executionGate.state == ScriptExecutionGate.State.RETIRED
        })
        generation.dispose()
    }

    @Test
    fun `generation freeze blocks every child execution gate`() {
        val second = object : EternalScript() {}
        val secondRuntime = managed(second)
        val generation = ScriptGeneration(
            listOf(managed(object : EternalScript() {}), secondRuntime),
            TestGenerationRuntimeResource()
        )

        assertTrue(generation.publish())
        assertEquals("second", secondRuntime.executionGate.withActive { "second" })

        assertTrue(generation.tryFreeze())
        assertNull(secondRuntime.executionGate.withActive { "blocked" })
        generation.retire()
        generation.dispose()
    }

    @Test
    fun `later enable failure deactivates attempted scripts in reverse order`() {
        val events = mutableListOf<String>()
        val generation = ScriptGeneration(
            listOf(
                managed(LifecycleProbeScript("first", events)),
                managed(LifecycleProbeScript("second", events, failEnable = true)),
                managed(LifecycleProbeScript("never", events))
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

private fun managed(script: EternalScript) = ManagedScriptRuntime(script)

private class LifecycleProbeScript(
    private val name: String,
    private val events: MutableList<String>,
    private val failEnable: Boolean = false
) : EternalScript() {
    override fun onEnable() {
        events += "enable:$name"
        if (failEnable) error("$name enable failed")
    }

    override fun onDisable() {
        events += "disable:$name"
    }
}
