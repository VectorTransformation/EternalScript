package eternalScript.core.script.generation

import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.project.ScriptProjectSource
import eternalScript.core.script.project.remapRuntimeStackTrace
import eternalScript.core.script.runtime.ManagedScriptRuntime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Lifecycle aggregate for one evaluated project generation.
 *
 * It owns one generation-wide admission gate, a deterministic list of
 * per-entry runtimes, and the shared runtime resource. Control-plane state is
 * changed at the generation boundary before any child gate is drained.
 */
internal class ScriptGeneration(
    runtimes: List<ManagedScriptRuntime>,
    private val runtimeResource: GenerationRuntimeResource
) {
    private val admissionGate = ScriptExecutionGate()
    private val disposal = GenerationDisposalState()

    internal val instances = runtimes.toList()
    val scripts = instances.map(ManagedScriptRuntime::script)

    init {
        require(this.scripts.isNotEmpty()) {
            "A script generation must contain at least one EternalScript entry."
        }

        val attached = mutableListOf<ManagedScriptRuntime>()
        try {
            instances.forEach { runtime ->
                runtime.executionGate.attachAdmissionGate(admissionGate)
                attached += runtime
            }
        } catch (exception: Throwable) {
            attached.asReversed().forEach { runtime ->
                runtime.executionGate.detachAdmissionGate(admissionGate)
            }
            throw exception
        }
    }

    val state: ScriptExecutionGate.State
        get() = admissionGate.state

    val isActive: Boolean
        get() = admissionGate.isActive && instances.all { current ->
            current.executionGate.isActive
        }

    val isDrained: Boolean
        get() = admissionGate.isDrained && instances.all { current ->
            current.executionGate.isDrained
        }

    val pluginDependencies: Set<String>
        get() = runtimeResource.pluginDependencies

    fun mapRuntimeExceptions(project: ScriptProjectSource) {
        instances.forEach { current ->
            current.executionGate.mapExceptions(project::remapRuntimeStackTrace)
        }
    }

    /** Freeze generation admission before changing any child gate. */
    fun tryFreeze(): Boolean {
        if (!admissionGate.tryFreeze()) return false

        val frozen = mutableListOf<ManagedScriptRuntime>()
        for (current in instances) {
            if (!current.executionGate.tryFreeze()) {
                frozen.asReversed().forEach { alreadyFrozen ->
                    alreadyFrozen.taskScope.open()
                    check(alreadyFrozen.executionGate.restore()) {
                        "A partially frozen EternalScript entry could not be restored."
                    }
                }
                check(admissionGate.restore()) {
                    "Generation admission could not be restored after a partial freeze."
                }
                return false
            }
            current.taskScope.close()
            frozen += current
        }
        return true
    }

    /** Publish children first and expose the generation only after all succeed. */
    fun publish(): Boolean {
        if (
            admissionGate.state != ScriptExecutionGate.State.STAGED ||
            instances.any { current ->
                current.executionGate.state != ScriptExecutionGate.State.STAGED
            }
        ) {
            instances.forEach { current -> current.taskScope.close() }
            return false
        }

        for (current in instances) {
            if (!current.executionGate.publish()) {
                admissionGate.retire()
                instances.forEach { runtime ->
                    runtime.taskScope.close()
                    runtime.executionGate.retire()
                }
                return false
            }
        }

        if (!admissionGate.publish()) {
            instances.forEach { current ->
                current.taskScope.close()
                current.executionGate.retire()
            }
            return false
        }
        return true
    }

    /** Restore child gates first and reopen admission only after all are ready. */
    fun restore(): Boolean {
        if (admissionGate.state != ScriptExecutionGate.State.SWAPPING) return false

        val restored = mutableListOf<ManagedScriptRuntime>()
        for (current in instances) {
            current.taskScope.open()
            if (!current.executionGate.restore()) {
                current.taskScope.close()
                restored.asReversed().forEach { alreadyRestored ->
                    alreadyRestored.taskScope.close()
                    check(alreadyRestored.executionGate.tryFreeze()) {
                        "A partially restored EternalScript entry could not be frozen again."
                    }
                }
                return false
            }
            restored += current
        }

        if (admissionGate.restore()) return true

        restored.asReversed().forEach { alreadyRestored ->
            alreadyRestored.taskScope.close()
            check(alreadyRestored.executionGate.tryFreeze()) {
                "An EternalScript entry could not be frozen after generation restore failed."
            }
        }
        return false
    }

    fun retire(): Boolean {
        var changed = admissionGate.retire()
        instances.forEach { current ->
            current.taskScope.close()
            changed = current.executionGate.retire() || changed
        }
        return changed
    }

    suspend fun cancelTrackedWorkAndJoin(timeoutMillis: Long): Boolean =
        coroutineScope {
            instances.map { instance ->
                    async {
                        instance.taskScope.cancelTrackedWorkAndJoin(timeoutMillis)
                    }
                }
                .awaitAll()
                .all { drained -> drained }
        }

    fun cancelTrackedWork() {
        instances.forEach { current -> current.taskScope.cancelTrackedWork() }
    }

    fun activate() {
        instances.forEach(ManagedScriptRuntime::activate)
    }

    fun deactivate() {
        val failures = mutableListOf<Throwable>()
        instances.asReversed().forEach { instance ->
            instance.deactivate(failures)
        }
        failures.throwCombined()
    }

    fun dispose() {
        disposal.dispose {
            val failures = mutableListOf<Throwable>()
            instances.forEach { instance ->
                instance.dispose(failures)
                cleanup(failures) {
                    instance.executionGate.detachAdmissionGate(admissionGate)
                }
            }
            cleanup(failures, runtimeResource::close)
            failures.throwCombined()
        }
    }
}

internal fun List<Throwable>.throwCombined() {
    firstOrNull()?.let { failure ->
        drop(1).forEach(failure::addSuppressed)
        throw failure
    }
}
