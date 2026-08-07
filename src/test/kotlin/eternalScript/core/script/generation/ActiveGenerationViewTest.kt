package eternalScript.core.script.generation

import eternalScript.api.script.EternalScript
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.project.ScriptProjectFile
import eternalScript.core.script.project.ScriptProjectSource
import eternalScript.core.script.runtime.ManagedScriptRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveGenerationViewTest {
    @Test
    fun `empty view has no owned generation`() {
        val snapshot = ActiveGenerationView { null }.snapshot()

        assertEquals(ScriptProjectGenerationSnapshot.EMPTY, snapshot)
        assertTrue(ActiveGenerationView { null }.pluginDependencies().isEmpty())
    }

    @Test
    fun `project identity remains visible while generation is swapping`() {
        val runtime = ScriptGeneration(
            listOf(ManagedScriptRuntime(object : EternalScript() {})),
            ViewGenerationRuntimeResource(setOf("Alpha", "Beta"))
        )
        val project = ScriptProjectSource.compose(
            listOf(ScriptProjectFile("main.kt", "class Main"))
        )
        val generation = ManagedProjectGeneration(project, runtime)
        val view = ActiveGenerationView { generation }

        assertEquals(ScriptExecutionGate.State.STAGED, view.snapshot().state)
        assertEquals(setOf("main.kt"), view.snapshot().sourceNames)
        assertEquals(1, view.snapshot().entryNames.size)

        assertTrue(runtime.publish())
        assertEquals(ScriptExecutionGate.State.ACTIVE, view.snapshot().state)
        assertEquals(setOf("Alpha", "Beta"), view.pluginDependencies())

        assertTrue(runtime.tryFreeze())
        val swapping = view.snapshot()
        assertEquals(ScriptExecutionGate.State.SWAPPING, swapping.state)
        assertEquals(setOf("main.kt"), swapping.sourceNames)
        assertEquals(1, swapping.entryNames.size)

        runtime.retire()
        runtime.dispose()
    }
}

private class ViewGenerationRuntimeResource(
    override val pluginDependencies: Set<String>
) : GenerationRuntimeResource {
    override fun close() = Unit
}
