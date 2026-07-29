package eternalScript.core.script

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptRuntimeResourceTest {
    @Test
    fun `runtime resource closes exactly once`() {
        val closeCount = AtomicInteger()
        val resource = ScriptRuntimeResource()
        resource.attach(AutoCloseable(closeCount::incrementAndGet))

        resource.close()
        resource.close()

        assertEquals(1, closeCount.get())
    }

    @Test
    fun `runtime resource cannot be silently replaced`() {
        val resource = ScriptRuntimeResource()
        resource.attach(AutoCloseable {})

        assertFailsWith<IllegalStateException> {
            resource.attach(AutoCloseable {})
        }

        resource.close()
    }

    @Test
    fun `runtime resource reports a close failure only once`() {
        val resource = ScriptRuntimeResource()
        val failure = IllegalStateException("close failed")
        resource.attach(AutoCloseable { throw failure })

        assertEquals(
            failure,
            assertFailsWith<IllegalStateException> {
                resource.close()
            }
        )
        resource.close()
    }
}
