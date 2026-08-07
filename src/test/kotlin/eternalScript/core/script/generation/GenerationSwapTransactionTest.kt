package eternalScript.core.script.generation

import eternalScript.api.script.EternalScript
import eternalScript.core.script.runtime.ManagedScriptRuntime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GenerationSwapTransactionTest {
    @Test
    fun `successful transaction publishes once and transfers retirement ownership`() {
        val state = GenerationStateStore()
        val current = generation("current").also { it.runtime.publish() }
        val replacement = generation("replacement")
        state.active.set(current)
        assertTrue(state.pendingCandidates.transfer(replacement))
        val transaction = GenerationSwapTransaction(state, current, replacement)

        assertTrue(transaction.publish { true })
        assertSame(replacement, state.active.get())
        assertFailsWith<IllegalStateException> {
            transaction.publish { true }
        }
        assertTrue(transaction.claimRetirement())

        current.runtime.retire()
        replacement.runtime.retire()
        current.runtime.dispose()
        replacement.runtime.dispose()
    }

    @Test
    fun `rollback restores the previous active generation after publication failure`() {
        val state = GenerationStateStore()
        val current = generation("current").also { it.runtime.publish() }
        val replacement = generation("replacement")
        state.active.set(current)
        assertTrue(state.pendingCandidates.transfer(replacement))
        assertTrue(state.pendingRetirements.transfer(current))
        val transaction = GenerationSwapTransaction(state, current, replacement)

        assertFailsWith<IllegalStateException> {
            transaction.publish { true }
        }
        transaction.rollbackPublication()

        assertSame(current, state.active.get())
        current.runtime.retire()
        replacement.runtime.retire()
        current.runtime.dispose()
        replacement.runtime.dispose()
    }

    private fun generation(name: String): ManagedProjectGeneration {
        val project = eternalScript.core.script.project.ScriptProjectSource.compose(
            listOf(
                eternalScript.core.script.project.ScriptProjectFile(
                    "$name.kt",
                    "class $name"
                )
            )
        )
        return ManagedProjectGeneration(
            project,
            ScriptGeneration(
                listOf(ManagedScriptRuntime(object : EternalScript() {})),
                TestSwapRuntimeResource
            )
        )
    }
}

private object TestSwapRuntimeResource : GenerationRuntimeResource {
    override val pluginDependencies: Set<String> = emptySet()
    override fun close() = Unit
}
