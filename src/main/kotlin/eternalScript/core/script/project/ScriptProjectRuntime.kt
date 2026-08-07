package eternalScript.core.script.project

import eternalScript.api.script.EternalScript
import eternalScript.core.script.generation.GenerationRuntimeResource
import eternalScript.core.script.generation.ScriptGeneration
import eternalScript.core.script.runtime.ManagedScriptRuntime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The evaluated runtime of one ordinary Kotlin project generation.
 *
 * A generation may contain any number of class-based [EternalScript] entries.
 * The generation owns the shared class-loader resource while each entry keeps
 * its own registration and execution state.
 */
internal class ScriptProjectRuntime(
    private val runtimes: List<ManagedScriptRuntime>,
    private val runtimeResource: GenerationRuntimeResource
) : AutoCloseable {
    private val ownershipTransferred = AtomicBoolean()

    init {
        require(runtimes.isNotEmpty()) {
            "An evaluated EternalScript project must create at least one script."
        }
    }

    /**
     * Atomically transfers this staged runtime to its sole lifecycle owner.
     * After a successful transfer, closing this staging object is harmless.
     */
    internal fun transfer(): ScriptGeneration {
        check(ownershipTransferred.compareAndSet(false, true)) {
            "The evaluated script runtime has already transferred ownership."
        }
        return try {
            ScriptGeneration(runtimes, runtimeResource)
        } catch (exception: Throwable) {
            ownershipTransferred.set(false)
            throw exception
        }
    }

    override fun close() {
        if (!ownershipTransferred.compareAndSet(false, true)) return
        var failure: Throwable? = null

        runtimes.forEach { runtime ->
            try {
                runtime.disposeRuntime()
            } catch (exception: Throwable) {
                if (failure == null) {
                    failure = exception
                } else {
                    failure.addSuppressed(exception)
                }
            }
        }

        try {
            runtimeResource.close()
        } catch (exception: Throwable) {
            if (failure == null) {
                failure = exception
            } else {
                failure.addSuppressed(exception)
            }
        }

        failure?.let { throw it }
    }
}
