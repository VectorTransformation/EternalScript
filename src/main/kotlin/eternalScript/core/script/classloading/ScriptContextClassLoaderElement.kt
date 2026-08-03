package eternalScript.core.script.classloading

import eternalScript.core.script.data.ScriptExecutionGate
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class ScriptContextClassLoaderElement(
    private val executionGate: ScriptExecutionGate
) : ThreadContextElement<ClassLoader?>,
    AbstractCoroutineContextElement(Key) {

    override fun updateThreadContext(context: CoroutineContext): ClassLoader? {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        executionGate.contextClassLoader()?.let { generationClassLoader ->
            thread.contextClassLoader = generationClassLoader
        }
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: ClassLoader?
    ) {
        Thread.currentThread().contextClassLoader = oldState
    }

    private companion object Key :
        CoroutineContext.Key<ScriptContextClassLoaderElement>
}
