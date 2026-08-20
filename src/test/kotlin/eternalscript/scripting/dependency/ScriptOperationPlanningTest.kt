package eternalscript.scripting.dependency

import eternalscript.scripting.repl.k2.ScriptDependencyGraph
import eternalscript.scripting.repl.k2.ScriptGraphResult
import eternalscript.scripting.source.ScriptTarget
import eternalscript.scripting.source.ScriptTargetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptOperationPlanningTest {
    @Test
    fun `load keeps active scripts and adds providers without inactive consumers`() {
        val graph = graph(
            listOf("provider", "target", "active", "inactive-consumer"),
            mapOf(
                "target" to setOf("provider"),
                "inactive-consumer" to setOf("provider")
            )
        )

        val plan = assertIs<ScriptLoadPlan.Ready>(
            planScriptLoad(graph, setOf("target"), setOf("active"))
        )

        assertEquals(setOf("active", "provider", "target"), plan.selectedPaths)
        assertTrue("inactive-consumer" !in plan.selectedPaths)
    }

    @Test
    fun `load reports missing target and active paths`() {
        val graph = graph(listOf("present"))

        val plan = assertIs<ScriptLoadPlan.MissingPaths>(
            planScriptLoad(graph, setOf("missing-target"), setOf("missing-active"))
        )

        assertEquals(setOf("missing-target"), plan.targetPaths)
        assertEquals(setOf("missing-active"), plan.activePaths)
    }

    @Test
    fun `unload blocks an outside consumer but allows consumer first or one containing folder`() {
        val graph = graph(
            listOf("group/provider", "group/consumer"),
            mapOf("group/consumer" to setOf("group/provider"))
        )
        val active = graph.paths

        val provider = planScriptUnload(
            graph,
            active,
            ScriptTarget("group/provider", ScriptTargetKind.FILE)
        )
        assertEquals(setOf("group/provider"), provider.selectedPaths)
        assertEquals(setOf("group/consumer"), provider.blockingConsumers)
        assertTrue(provider.missingGraphPaths.isEmpty())

        val consumer = planScriptUnload(
            graph,
            active,
            ScriptTarget("group/consumer", ScriptTargetKind.FILE)
        )
        assertTrue(consumer.blockingConsumers.isEmpty())

        val folder = planScriptUnload(
            graph,
            active,
            ScriptTarget("group", ScriptTargetKind.DIRECTORY)
        )
        assertEquals(active.toSet(), folder.selectedPaths)
        assertTrue(folder.blockingConsumers.isEmpty())
    }

    @Test
    fun `unload rejects selecting only part of a dependency component`() {
        val graph = graph(
            listOf("a", "b"),
            mapOf(
                "a" to setOf("b"),
                "b" to setOf("a")
            )
        )

        val plan = planScriptUnload(
            graph,
            graph.paths,
            ScriptTarget("a", ScriptTargetKind.FILE)
        )

        assertEquals(setOf("a"), plan.selectedPaths)
        assertEquals(setOf("b"), plan.blockingConsumers)
    }

    @Test
    fun `unload fails closed when the active graph is unavailable`() {
        val plan = planScriptUnload(
            null,
            listOf("provider"),
            ScriptTarget("provider", ScriptTargetKind.FILE)
        )

        assertEquals(setOf("provider"), plan.missingGraphPaths)
    }

    private fun graph(
        paths: List<String>,
        dependencies: Map<String, Set<String>> = emptyMap()
    ): ScriptDependencyGraph = assertIs<ScriptGraphResult.Success>(
        ScriptDependencyGraph.create(
            paths,
            dependencies,
            initializationDependencies = emptyMap()
        )
    ).graph
}
