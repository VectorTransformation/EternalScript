package eternalScript.core.script

import java.util.concurrent.atomic.AtomicReference

internal class ScriptRuntimeResource : AutoCloseable {
    private val resource = AtomicReference<AutoCloseable?>()

    fun attach(value: AutoCloseable) {
        check(resource.compareAndSet(null, value)) {
            "A script runtime resource is already attached."
        }
    }

    override fun close() {
        resource.getAndSet(null)?.close()
    }
}
