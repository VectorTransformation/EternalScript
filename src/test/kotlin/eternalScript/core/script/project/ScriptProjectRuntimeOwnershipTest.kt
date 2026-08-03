package eternalScript.core.script.project

import eternalScript.api.script.Script
import eternalScript.core.script.generation.GenerationRuntimeResource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptProjectRuntimeOwnershipTest {
    @Test
    fun `transferred runtime is closed only by the managed generation`() {
        val closes = AtomicInteger()
        val runtime = ScriptProjectRuntime(
            scripts = listOf(object : Script() {}),
            runtimeResource = CloseCountingGenerationResource(closes)
        )

        val generation = runtime.transfer()
        runtime.close()
        assertEquals(0, closes.get())

        generation.retire()
        generation.dispose()
        generation.dispose()
        runtime.close()
        assertEquals(1, closes.get())
    }
}

private class CloseCountingGenerationResource(
    private val closes: AtomicInteger
) : GenerationRuntimeResource {
    override val pluginDependencies: Set<String> = emptySet()

    override fun close() {
        closes.incrementAndGet()
    }
}
