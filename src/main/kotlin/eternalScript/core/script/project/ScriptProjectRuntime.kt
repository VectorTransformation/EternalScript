package eternalScript.core.script.project

import eternalScript.api.script.Script
import eternalScript.core.script.generation.GenerationRuntimeResource
import eternalScript.core.script.generation.ScriptGeneration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The evaluated runtime of one ordinary Kotlin project generation.
 *
 * A generation may contain any number of class-based [Script] instances. The
 * generation owns the shared class-loader resource while each script keeps its
 * own registration and execution state.
 */
internal class ScriptProjectRuntime(
    val scripts: List<Script>,
    private val runtimeResource: GenerationRuntimeResource
) : AutoCloseable {
    private val ownershipTransferred = AtomicBoolean()

    init {
        require(scripts.isNotEmpty()) {
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
            ScriptGeneration(scripts, runtimeResource)
        } catch (exception: Throwable) {
            ownershipTransferred.set(false)
            throw exception
        }
    }

    override fun close() {
        if (!ownershipTransferred.compareAndSet(false, true)) return
        var failure: Throwable? = null

        scripts.forEach { script ->
            try {
                script.disposeRuntime()
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
