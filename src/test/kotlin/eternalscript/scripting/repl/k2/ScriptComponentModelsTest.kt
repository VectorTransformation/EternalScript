package eternalscript.scripting.repl.k2

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ScriptComponentModelsTest {
    @Test
    fun `generation close attempts every component and is idempotent`() {
        val directory = Files.createTempDirectory("eternalscript-generation-close")
        val first = CompiledComponentArtifact.create(directory, "first", emptyMap())
        val second = CompiledComponentArtifact.create(directory, "second", emptyMap())
        val generation = generationOf(
            "first" to first,
            "second" to second
        )
        try {
            first.close()
            second.close()

            val failure = assertFailsWith<IllegalStateException> { generation.close() }

            assertEquals(1, failure.suppressed.size)
            generation.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed retain releases components retained before the failure`() {
        val directory = Files.createTempDirectory("eternalscript-generation-retain")
        val retainedFirst = CompiledComponentArtifact.create(directory, "retained-first", emptyMap())
        val alreadyDisposed = CompiledComponentArtifact.create(directory, "already-disposed", emptyMap())
        val generation = generationOf(
            "retained-first" to retainedFirst,
            "already-disposed" to alreadyDisposed
        )
        try {
            alreadyDisposed.close()

            assertFailsWith<IllegalStateException> { generation.retained() }
            assertFailsWith<IllegalStateException> { generation.close() }

            assertFalse(Files.exists(retainedFirst.jar))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun generationOf(
        vararg artifacts: Pair<String, CompiledComponentArtifact>
    ): CompiledComponentGeneration {
        val components = artifacts.map { (id, artifact) ->
            ScriptComponent(id, emptyList(), emptySet()) to artifact
        }
        val graph = ScriptDependencyGraph(
            paths = emptyList(),
            dependencies = emptyMap(),
            initializationDependencies = emptyMap(),
            components = components.map { (component, _) -> component },
            initializationOrder = emptyList()
        )
        return CompiledComponentGeneration(
            graph = graph,
            sources = emptyList(),
            components = components.associate { (component, artifact) ->
                component.id to CompiledComponent(component, emptyList(), emptyList(), artifact)
            }
        )
    }
}
