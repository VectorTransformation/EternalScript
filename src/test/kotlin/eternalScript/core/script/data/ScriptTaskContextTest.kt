package eternalScript.core.script.data

import eternalScript.core.script.classloading.ScriptContextClassLoaderElement
import eternalScript.api.script.EternalScript
import eternalScript.core.script.runtime.ManagedScriptRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.Test

class ScriptTaskContextTest {
    @Test
    fun `runnable task uses generation class loader and restores caller`() {
        val script = TestScript()
        val runtime = ManagedScriptRuntime(script)
        val generationLoader = URLClassLoader(emptyArray(), javaClass.classLoader)
        val callerLoader = Thread.currentThread().contextClassLoader
        val observed = AtomicReference<ClassLoader?>()
        runtime.executionGate.attachContextClassLoader(generationLoader)
        runtime.executionGate.publish()

        script.task {
            observed.set(Thread.currentThread().contextClassLoader)
        }.run()

        assertSame(generationLoader, observed.get())
        assertSame(callerLoader, Thread.currentThread().contextClassLoader)
        generationLoader.close()
    }

    @Test
    fun `consumer task is skipped after generation freeze`() {
        val script = TestScript()
        val runtime = ManagedScriptRuntime(script)
        val observed = AtomicReference<String?>()
        runtime.executionGate.publish()
        val callback = script.task<String>(observed::set)

        assertEquals(true, runtime.executionGate.tryFreeze())
        callback.accept("should-not-run")

        assertNull(observed.get())
    }

    @Test
    fun `coroutine context restores generation class loader on resume`() = runBlocking {
        val gate = ScriptExecutionGate()
        val generationLoader = URLClassLoader(emptyArray(), javaClass.classLoader)
        val callerLoader = Thread.currentThread().contextClassLoader
        val observed = withContext(
            Dispatchers.Default + ScriptContextClassLoaderElement(gate).also {
                gate.attachContextClassLoader(generationLoader)
            }
        ) {
            Thread.currentThread().contextClassLoader
        }

        assertSame(generationLoader, observed)
        assertSame(callerLoader, Thread.currentThread().contextClassLoader)
        generationLoader.close()
    }

    private class TestScript : EternalScript()
}
