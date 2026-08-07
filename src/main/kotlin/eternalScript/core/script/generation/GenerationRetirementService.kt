package eternalScript.core.script.generation

import eternalScript.core.script.data.ScriptExecutionGate
import kotlinx.coroutines.delay
import java.util.logging.Logger

/** Drains, deactivates, and disposes retired generation resources. */
internal class GenerationRetirementService(
    private val stateStore: GenerationStateStore,
    private val diagnostics: GenerationDiagnostics,
    private val logger: Logger
) {
    suspend fun shutdown(
        generation: ManagedProjectGeneration,
        report: MutableScriptProjectReport
    ) {
        val frozen = generation.runtime.tryFreeze()
        val callbacksDrained = if (
            frozen || generation.runtime.state == ScriptExecutionGate.State.SWAPPING
        ) {
            awaitDrain(generation.runtime, GENERATION_SHUTDOWN_DRAIN_ATTEMPTS)
        } else {
            generation.runtime.isDrained
        }
        val trackedWorkDrained = generation.runtime.cancelTrackedWorkAndJoin(
            TRACKED_WORK_SHUTDOWN_TIMEOUT_MILLIS
        )
        if (!callbacksDrained || !trackedWorkDrained) {
            logger.warning(
                "Script generation shutdown exceeded its drain deadline " +
                    "(callbacks=$callbacksDrained, trackedWork=$trackedWorkDrained); " +
                    "forced cleanup will continue because the plugin is disabling."
            )
        }

        generation.runtime.retire()
        try {
            generation.runtime.deactivate()
        } catch (exception: Throwable) {
            diagnostics.lifecycleFailure(
                generation.project,
                ScriptLifecycleFailurePhase.DISABLE,
                "shutdown disable",
                exception,
                report
            )
        } finally {
            dispose(generation, "shutdown cleanup", report)
        }
    }

    suspend fun awaitDrain(runtime: ScriptGeneration, attempts: Int): Boolean {
        repeat(attempts) {
            if (runtime.isDrained) return true
            delay(GENERATION_FREEZE_RETRY_MILLIS)
        }
        return runtime.isDrained
    }

    fun dispose(
        generation: ManagedProjectGeneration,
        technicalPhase: String,
        report: MutableScriptProjectReport
    ) {
        try {
            generation.runtime.dispose()
        } catch (exception: Throwable) {
            diagnostics.lifecycleFailure(
                generation.project,
                ScriptLifecycleFailurePhase.CLEANUP,
                technicalPhase,
                exception,
                report
            )
        } finally {
            stateStore.invalidatedGenerations.remove(generation)
        }
    }
}

internal const val GENERATION_FREEZE_ATTEMPTS = 50
internal const val GENERATION_SHUTDOWN_DRAIN_ATTEMPTS = 1_000
internal const val GENERATION_FREEZE_RETRY_MILLIS = 10L
internal const val TRACKED_WORK_DRAIN_TIMEOUT_MILLIS = 2_000L
internal const val TRACKED_WORK_SHUTDOWN_TIMEOUT_MILLIS = 10_000L
