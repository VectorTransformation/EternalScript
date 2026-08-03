package eternalScript.core.script.generation

import eternalScript.api.script.Script
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.project.ScriptProjectSource
import eternalScript.core.script.project.remapRuntimeStackTrace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Lifecycle aggregate for one evaluated project generation.
 *
 * It owns one generation-wide admission gate, a deterministic list of
 * per-Script runtimes, and the shared runtime resource. Control-plane state is
 * changed at the generation boundary before any child gate is drained.
 */
internal class ScriptGeneration(
    scripts: List<Script>,
    private val runtimeResource: GenerationRuntimeResource
) {
    private val admissionGate = ScriptExecutionGate()
    private val disposal = GenerationDisposalState()

    private val instances: List<ScriptInstanceRuntime> = scripts.map(::ScriptInstanceRuntime)
    val scripts: List<Script> = instances.map(ScriptInstanceRuntime::script)

    init {
        require(this.scripts.isNotEmpty()) {
            "A script generation must contain at least one Script instance."
        }

        val attached = mutableListOf<Script>()
        try {
            this.scripts.forEach { script ->
                script.executionGate.attachAdmissionGate(admissionGate)
                attached += script
            }
        } catch (exception: Throwable) {
            attached.asReversed().forEach { script ->
                script.executionGate.detachAdmissionGate(admissionGate)
            }
            throw exception
        }
    }

    val state: ScriptExecutionGate.State
        get() = admissionGate.state

    val isActive: Boolean
        get() = admissionGate.isActive && scripts.all { current ->
            current.executionGate.isActive
        }

    val isDrained: Boolean
        get() = admissionGate.isDrained && scripts.all { current ->
            current.executionGate.isDrained
        }

    val pluginDependencies: Set<String>
        get() = runtimeResource.pluginDependencies

    fun mapRuntimeExceptions(project: ScriptProjectSource) {
        scripts.forEach { current ->
            current.executionGate.mapExceptions(project::remapRuntimeStackTrace)
        }
    }

    /** Freeze generation admission before changing any child gate. */
    fun tryFreeze(): Boolean {
        if (!admissionGate.tryFreeze()) return false

        val frozen = mutableListOf<Script>()
        for (current in scripts) {
            if (!current.executionGate.tryFreeze()) {
                frozen.asReversed().forEach { alreadyFrozen ->
                    alreadyFrozen.taskScope.open()
                    check(alreadyFrozen.executionGate.restore()) {
                        "A partially frozen Script could not be restored."
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
            scripts.any { current ->
                current.executionGate.state != ScriptExecutionGate.State.STAGED
            }
        ) {
            scripts.forEach { current -> current.taskScope.close() }
            return false
        }

        for (current in scripts) {
            if (!current.executionGate.publish()) {
                admissionGate.retire()
                scripts.forEach { script ->
                    script.taskScope.close()
                    script.executionGate.retire()
                }
                return false
            }
        }

        if (!admissionGate.publish()) {
            scripts.forEach { current ->
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

        val restored = mutableListOf<Script>()
        for (current in scripts) {
            current.taskScope.open()
            if (!current.executionGate.restore()) {
                current.taskScope.close()
                restored.asReversed().forEach { alreadyRestored ->
                    alreadyRestored.taskScope.close()
                    check(alreadyRestored.executionGate.tryFreeze()) {
                        "A partially restored Script could not be frozen again."
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
                "A Script could not be frozen after generation restore failed."
            }
        }
        return false
    }

    fun retire(): Boolean {
        var changed = admissionGate.retire()
        scripts.forEach { current ->
            current.taskScope.close()
            changed = current.executionGate.retire() || changed
        }
        return changed
    }

    suspend fun cancelTrackedWorkAndJoin(timeoutMillis: Long): Boolean =
        coroutineScope {
            instances
                .map { instance ->
                    async {
                        instance.script.taskScope.cancelTrackedWorkAndJoin(timeoutMillis)
                    }
                }
                .awaitAll()
                .all { drained -> drained }
        }

    fun cancelTrackedWork() {
        scripts.forEach { current -> current.taskScope.cancelTrackedWork() }
    }

    fun activate() {
        instances.forEach(ScriptInstanceRuntime::activate)
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
                    instance.script.executionGate.detachAdmissionGate(admissionGate)
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
