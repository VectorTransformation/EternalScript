package eternalScript.core.script.generation

import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.project.ScriptProjectSource
import eternalScript.core.runtime.GlobalExecution
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.logging.Logger

/**
 * Owns the transactional lifecycle of one active script generation.
 * Compilation and evaluation are delegated to the project backend, while
 * this coordinator controls epoch fences, ownership, activation, replacement,
 * rollback, and disposal.
 */
internal class GenerationLifecycleEngine(
    private val stager: GenerationStager,
    private val globalExecution: GlobalExecution,
    private val diagnostics: GenerationDiagnostics,
    private val logger: Logger,
    private val state: GenerationStateStore,
    private val retirement: GenerationRetirementService
) {
    private val active get() = state.active
    private val activeView get() = state.activeView
    private val pendingCandidates get() = state.pendingCandidates
    private val pendingRetirements get() = state.pendingRetirements
    private val invalidatedGenerations get() = state.invalidatedGenerations

    internal fun open() {
        state.open()
    }

    internal fun close() {
        state.close()
    }

    internal fun invalidateEnvironment() {
        state.invalidateEnvironment()
    }

    fun stop() {
        close()
        val current = active.getAndSet(null)
        val candidates = pendingCandidates.claimAll()
        val retirements = pendingRetirements.claimAll()
        if (current == null && candidates.isEmpty() && retirements.isEmpty()) return
        val report = MutableScriptProjectReport(logSummaries = true)
        runBlocking {
            current?.let { retirement.shutdown(it, report) }
            candidates.forEach { candidate ->
                retirement.shutdown(candidate, report)
            }
            retirements.forEach { retirement ->
                this@GenerationLifecycleEngine.retirement.shutdown(retirement, report)
            }
        }
    }

    private fun openEpoch(): Long? = state.openEpoch()

    private fun isOpen(epoch: Long): Boolean =
        state.lifecycle.accepts(epoch)

    internal suspend fun load(project: ScriptProjectSource): ScriptProjectLoadResult {
        val expected = active.get()
        val previousGeneration = activeView.snapshot()
        val report = MutableScriptProjectReport()
        val activated = loadTransaction(project, expected, report)
        val current = active.get()
        val generation = activeView.snapshot()
        val outcome = resolveScriptProjectLoadOutcome(
            activated = activated,
            expected = expected,
            current = current,
            generation = generation
        )
        return ScriptProjectLoadResult(
            outcome = outcome,
            previousGeneration = previousGeneration,
            generation = generation,
            report = report.snapshot()
        )
    }

    private suspend fun loadTransaction(
        project: ScriptProjectSource,
        expected: ManagedProjectGeneration?,
        report: MutableScriptProjectReport
    ): Boolean {
        val epoch = openEpoch() ?: return false
        if (expected != null && !expected.runtime.isActive) return false
        val environment = state.environmentEpoch()
        val compiled = stager.compile(project, report)

        if (!canCommit(epoch, environment) || active.get() !== expected) return false
        if (compiled == null) return false

        return withContext(NonCancellable) {
            val replacement = stager.stage(
                project = project,
                compiled = compiled,
                report = report,
                canStage = {
                    canCommit(epoch, environment) && active.get() === expected
                },
                takeOwnership = pendingCandidates::transfer
            ) ?: return@withContext false

            if (!canCommit(epoch, environment) || active.get() !== expected) {
                globalExecution.global {
                    discard(replacement, report)
                }
                return@withContext false
            }

            if (expected == null) {
                activateInitial(replacement, epoch, environment, report)
            } else {
                replace(expected, replacement, epoch, environment, report)
            }
        }
    }

    private suspend fun activateInitial(
        replacement: ManagedProjectGeneration,
        epoch: Long,
        environment: Long,
        report: MutableScriptProjectReport
    ): Boolean {
        if (!canCommit(epoch, environment)) {
            globalExecution.global {
                discard(replacement, report)
            }
            return false
        }

        var activationAttempted = false
        var activationFailure: Throwable? = null
        val activated = try {
            globalExecution.global {
                if (!canCommit(epoch, environment)) {
                    false
                } else {
                    activationAttempted = true
                    replacement.runtime.activate()
                    if (
                        !canCommit(epoch, environment) ||
                        !active.compareAndSet(null, replacement)
                    ) {
                        false
                    } else if (
                        !canCommit(epoch, environment) ||
                        !replacement.runtime.publish()
                    ) {
                        active.compareAndSet(replacement, null)
                        false
                    } else {
                        check(pendingCandidates.claim(replacement)) {
                            "The activated script candidate lost transaction ownership."
                        }
                        true
                    }
                }
            }
        } catch (exception: Throwable) {
            activationFailure = exception
            false
        }

        if (activated) return true

        active.compareAndSet(replacement, null)
        activationFailure?.let { exception ->
            globalExecution.global {
                diagnostics.lifecycleFailure(
                    replacement.project,
                    ScriptLifecycleFailurePhase.ENABLE,
                    "initial enable",
                    exception,
                    report
                )
            }
        }
        if (activationAttempted) {
            cleanupFailedActivation(replacement, report)
        } else {
            globalExecution.global {
                discard(replacement, report)
            }
        }
        return false
    }

    private suspend fun replace(
        current: ManagedProjectGeneration,
        replacement: ManagedProjectGeneration,
        epoch: Long,
        environment: Long,
        report: MutableScriptProjectReport
    ): Boolean {
        var ownsReplacement = true
        var ownsFrozenCurrent = false
        var trackedWorkCancellationStarted = false
        try {
            if (!canCommit(epoch, environment) || !freeze(current, epoch)) {
                return false
            }
            ownsFrozenCurrent = true

            trackedWorkCancellationStarted = true
            val trackedWorkDrained = current.runtime.cancelTrackedWorkAndJoin(
                TRACKED_WORK_DRAIN_TIMEOUT_MILLIS
            )
            if (!trackedWorkDrained) {
                logger.warning(
                    "Tracked script work did not stop within ${TRACKED_WORK_DRAIN_TIMEOUT_MILLIS}ms; " +
                        "the generation replacement was aborted. Cleanup is deferred with new " +
                        "script entries blocked because cancelled work cannot be restored."
                )
                ownsFrozenCurrent = false
                return false
            }

            if (
                !canCommit(epoch, environment) ||
                active.get() !== current
            ) {
                return false
            }
            val result = replaceFrozen(
                current,
                replacement,
                epoch,
                environment,
                report
            )
            ownsReplacement = false
            ownsFrozenCurrent = false
            return result
        } finally {
            if (ownsReplacement) {
            globalExecution.global {
                    discard(replacement, report)
                }
            }
            if (
                ownsFrozenCurrent &&
                isOpen(epoch) &&
                active.get() === current &&
                current !in invalidatedGenerations
            ) {
                when (frozenGenerationAbortAction(trackedWorkCancellationStarted)) {
                    FrozenGenerationAbortAction.RESTORE -> current.runtime.restore()
                    FrozenGenerationAbortAction.KEEP_FROZEN -> Unit
                }
            }
        }
    }

    private suspend fun freeze(
        generation: ManagedProjectGeneration,
        epoch: Long
    ): Boolean {
        if (!isOpen(epoch) || active.get() !== generation) return false
        if (!generation.runtime.tryFreeze()) return false

        var ownsFrozenGeneration = true
        try {
            if (retirement.awaitDrain(generation.runtime, GENERATION_FREEZE_ATTEMPTS)) {
                ownsFrozenGeneration = false
                return true
            }
            return false
        } finally {
            if (
                ownsFrozenGeneration &&
                isOpen(epoch) &&
                active.get() === generation &&
                generation !in invalidatedGenerations
            ) {
                generation.runtime.restore()
            }
        }
    }

    private suspend fun replaceFrozen(
        current: ManagedProjectGeneration,
        replacement: ManagedProjectGeneration,
        epoch: Long,
        environment: Long,
        report: MutableScriptProjectReport
    ): Boolean = withOwnershipCleanup(
        cleanup = {
            globalExecution.global {
                discard(replacement, report)
            }
        }
    ) {
        replaceFrozenTransaction(
            current,
            replacement,
            epoch,
            environment,
            report
        )
    }

    /** Every exit consumes or discards the staged candidate ownership. */
    private suspend fun replaceFrozenTransaction(
        current: ManagedProjectGeneration,
        replacement: ManagedProjectGeneration,
        epoch: Long,
        environment: Long,
        report: MutableScriptProjectReport
    ): Boolean {
        val deactivated = try {
            globalExecution.global {
                if (!canCommit(epoch, environment) || active.get() !== current) {
                    false
                } else {
                    current.runtime.deactivate()
                    true
                }
            }
        } catch (exception: Throwable) {
            globalExecution.global {
                diagnostics.lifecycleFailure(
                    current.project,
                    ScriptLifecycleFailurePhase.DISABLE,
                    "replacement disable",
                    exception,
                    report
                )
                discard(replacement, report)
            }
            restore(current, epoch, report)
            return false
        }
        if (!deactivated) return false

        val activationAttempted = try {
            globalExecution.global {
                if (!canCommit(epoch, environment) || active.get() !== current) {
                    false
                } else {
                    // This is the final closed/epoch fence before candidate
                    // activation. Both close() and lifecycle callbacks execute
                    // on the global thread, so no shutdown commit can cross it.
                    replacement.runtime.activate()
                    true
                }
            }
        } catch (exception: Throwable) {
            globalExecution.global {
                diagnostics.lifecycleFailure(
                    replacement.project,
                    ScriptLifecycleFailurePhase.ENABLE,
                    "replacement enable",
                    exception,
                    report
                )
            }
            cleanupFailedActivation(replacement, report)
            restore(current, epoch, report)
            return false
        }
        if (!activationAttempted) {
            globalExecution.global {
                discard(replacement, report)
            }
            restore(current, epoch, report)
            return false
        }

        val transaction = GenerationSwapTransaction(state, current, replacement)
        val published = try {
            globalExecution.global {
                transaction.publish {
                    canCommit(epoch, environment)
                }
            }
        } catch (exception: Throwable) {
            transaction.rollbackPublication()
            globalExecution.global {
                diagnostics.lifecycleFailure(
                    replacement.project,
                    ScriptLifecycleFailurePhase.PUBLISH,
                    "replacement publish",
                    exception,
                    report
                )
            }
            false
        }

        if (!published) {
            cleanupFailedActivation(replacement, report)
            restore(current, epoch, report)
            return false
        }

        globalExecution.global {
            if (!transaction.claimRetirement()) return@global
            current.runtime.retire()
            retirement.dispose(current, "retire cleanup", report)
        }
        return true
    }

    private suspend fun restore(
        generation: ManagedProjectGeneration,
        epoch: Long,
        report: MutableScriptProjectReport
    ): Boolean {
        if (generation in invalidatedGenerations) {
            return false
        }
        return try {
            globalExecution.global {
                if (generation in invalidatedGenerations) return@global false
                if (!isOpen(epoch) || active.get() !== generation) {
                    generation.runtime.retire()
                    retirement.dispose(generation, "restore cleanup", report)
                    active.compareAndSet(generation, null)
                    return@global false
                }

                generation.runtime.activate()
                check(generation.runtime.restore()) {
                    "The previous script generation could not leave the swapping state."
                }
                true
            }
        } catch (exception: Throwable) {
            val cleanupFailures = mutableListOf<Throwable>()
            try {
                globalExecution.global {
                    runCatching(generation.runtime::retire)
                        .exceptionOrNull()
                        ?.let(cleanupFailures::add)
                }
            } catch (cleanupFailure: Throwable) {
                cleanupFailures += cleanupFailure
            }

            val trackedWorkDrained = try {
                generation.runtime.cancelTrackedWorkAndJoin(TRACKED_WORK_DRAIN_TIMEOUT_MILLIS)
            } catch (cleanupFailure: Throwable) {
                cleanupFailures += cleanupFailure
                false
            }
            if (!trackedWorkDrained) {
                logger.warning(
                    "Restored script-generation work did not stop within " +
                        "${TRACKED_WORK_DRAIN_TIMEOUT_MILLIS}ms; forced cleanup will continue."
                )
            }
            cleanupFailures.forEach(exception::addSuppressed)

            globalExecution.global {
                runCatching(generation.runtime::deactivate)
                    .exceptionOrNull()
                    ?.let { cleanupFailure ->
                        cleanupFailures += cleanupFailure
                        exception.addSuppressed(cleanupFailure)
                    }
                cleanupFailures.forEach { cleanupFailure ->
                    diagnostics.lifecycleFailure(
                        generation.project,
                        ScriptLifecycleFailurePhase.CLEANUP,
                        "restore cleanup",
                        cleanupFailure,
                        report
                    )
                }
                diagnostics.lifecycleFailure(
                    generation.project,
                    ScriptLifecycleFailurePhase.RESTORE,
                    "restore",
                    exception,
                    report
                )
                retirement.dispose(generation, "restore cleanup", report)
                active.compareAndSet(generation, null)
            }
            false
        }
    }

    private fun canCommit(epoch: Long, environment: Long): Boolean =
        state.accepts(epoch, environment)

    private suspend fun cleanupFailedActivation(
        generation: ManagedProjectGeneration,
        report: MutableScriptProjectReport
    ) {
        val runtime = generation.runtime
        runtime.retire()
        val trackedWorkDrained = try {
            runtime.cancelTrackedWorkAndJoin(TRACKED_WORK_DRAIN_TIMEOUT_MILLIS)
        } catch (exception: Throwable) {
            globalExecution.global {
                diagnostics.lifecycleFailure(
                    generation.project,
                    ScriptLifecycleFailurePhase.CLEANUP,
                    "cleanup drain",
                    exception,
                    report
                )
            }
            false
        }
        if (!trackedWorkDrained) {
            logger.warning(
                "Failed script-generation work did not stop within " +
                    "${TRACKED_WORK_DRAIN_TIMEOUT_MILLIS}ms; forced cleanup will continue."
            )
        }
        globalExecution.global {
            if (!pendingCandidates.claim(generation)) return@global
            try {
                runtime.deactivate()
            } catch (exception: Throwable) {
                diagnostics.lifecycleFailure(
                    generation.project,
                    ScriptLifecycleFailurePhase.CLEANUP,
                    "failed activation cleanup",
                    exception,
                    report
                )
            } finally {
                retirement.dispose(generation, "failed activation cleanup", report)
            }
        }
    }

    private fun discard(
        generation: ManagedProjectGeneration,
        report: MutableScriptProjectReport
    ) {
        if (!pendingCandidates.claim(generation)) return
        generation.runtime.retire()
        retirement.dispose(generation, "discard cleanup", report)
    }

    internal suspend fun check(project: ScriptProjectSource): ScriptProjectCheckResult {
        val report = MutableScriptProjectReport()
        val compiled = stager.compile(project, report)
        return ScriptProjectCheckResult(
            outcome = if (compiled != null) {
                ScriptProjectCheckOutcome.PASSED
            } else {
                ScriptProjectCheckOutcome.FAILED
            },
            report = report.snapshot()
        )
    }

    internal suspend fun clearNow(): ScriptProjectUnloadResult {
        val before = activeView.snapshot()
        val sourceCount = before.sourceNames.size
        val entryCount = before.entryNames.size
        val report = MutableScriptProjectReport()
        val cleared = clearNowTransaction(report)
        val outcome = when {
            before.state == null && cleared == 0 -> ScriptProjectUnloadOutcome.ALREADY_EMPTY
            cleared != null -> ScriptProjectUnloadOutcome.UNLOADED
            else -> ScriptProjectUnloadOutcome.REJECTED
        }
        return ScriptProjectUnloadResult(
            outcome = outcome,
            sourceCount = sourceCount,
            entryCount = entryCount,
            generation = activeView.snapshot(),
            report = report.snapshot()
        )
    }

    private suspend fun clearNowTransaction(report: MutableScriptProjectReport): Int? {
        val epoch = openEpoch() ?: return null
        val current = active.get() ?: return 0
        var ownsFrozenCurrent = false
        var trackedWorkCancellationStarted = false
        try {
            val frozen = when (clearPreparation(current.runtime.state)) {
                FrozenGenerationClearPreparation.FREEZE -> freeze(current, epoch)
                FrozenGenerationClearPreparation.RETRY_DRAIN ->
                    retirement.awaitDrain(current.runtime, GENERATION_FREEZE_ATTEMPTS)
                FrozenGenerationClearPreparation.REJECT -> false
            }
            if (!frozen) return null
            ownsFrozenCurrent = true

            trackedWorkCancellationStarted = true
            val trackedWorkDrained = current.runtime.cancelTrackedWorkAndJoin(
                TRACKED_WORK_DRAIN_TIMEOUT_MILLIS
            )
            if (!trackedWorkDrained) {
                logger.warning(
                    "Tracked script work did not stop within ${TRACKED_WORK_DRAIN_TIMEOUT_MILLIS}ms; " +
                        "the generation remains blocked and cleanup is deferred."
                )
                ownsFrozenCurrent = false
                return null
            }

            return globalExecution.global clear@{
                if (!isOpen(epoch) || active.get() !== current) return@clear null
                val count = clearGeneration(current, report)
                ownsFrozenCurrent = false
                count
            }
        } finally {
            if (
                ownsFrozenCurrent &&
                isOpen(epoch) &&
                active.get() === current &&
                current !in invalidatedGenerations
            ) {
                when (frozenGenerationAbortAction(trackedWorkCancellationStarted)) {
                    FrozenGenerationAbortAction.RESTORE -> current.runtime.restore()
                    FrozenGenerationAbortAction.KEEP_FROZEN -> Unit
                }
            }
        }
    }

    internal fun activePluginDependencies(): Set<String> =
        activeView.pluginDependencies()

    /**
     * Plugin disable events are synchronous. Freeze the active generation in
     * the event callback so no new script callback can enter while the
     * generation is drained and disposed asynchronously.
     */
    internal fun freezeForDisabledPlugin(pluginName: String): Boolean {
        val current = active.get() ?: return false
        if (!current.usesPlugin(pluginName)) return false
        invalidatedGenerations.add(current)
        val frozen = when (current.runtime.state) {
            ScriptExecutionGate.State.ACTIVE -> current.runtime.tryFreeze()
            ScriptExecutionGate.State.SWAPPING -> true
            ScriptExecutionGate.State.STAGED,
            ScriptExecutionGate.State.RETIRED -> false
        }
        if (frozen || current.runtime.state == ScriptExecutionGate.State.SWAPPING) {
            current.runtime.cancelTrackedWork()
        }
        return true
    }

    internal suspend fun unloadForDisabledPlugins(pluginNames: Set<String>): Int? {
        if (pluginNames.isEmpty()) return 0
        val epoch = openEpoch() ?: return null
        val current = active.get() ?: return 0
        if (pluginNames.none { pluginName -> current.usesPlugin(pluginName) }) return 0

        if (
            current.runtime.state == ScriptExecutionGate.State.ACTIVE &&
            !current.runtime.tryFreeze()
        ) {
            return null
        }
        if (current.runtime.state != ScriptExecutionGate.State.SWAPPING) {
            return null
        }

        val callbacksDrained = retirement.awaitDrain(
            current.runtime,
            GENERATION_SHUTDOWN_DRAIN_ATTEMPTS
        )
        val trackedWorkDrained = current.runtime.cancelTrackedWorkAndJoin(
            TRACKED_WORK_SHUTDOWN_TIMEOUT_MILLIS
        )
        if (!callbacksDrained || !trackedWorkDrained) {
            logger.warning(
                "A plugin used by the active script generation was disabled, but " +
                    "generation drain exceeded its deadline " +
                    "(callbacks=$callbacksDrained, trackedWork=$trackedWorkDrained). " +
                    "Cleanup is deferred with new script entries blocked."
            )
            return null
        }

        val report = MutableScriptProjectReport(logSummaries = true)
        return globalExecution.global clear@{
            if (!isOpen(epoch) || active.get() !== current) return@clear null
            clearGeneration(current, report)
        }
    }

    private fun ManagedProjectGeneration.usesPlugin(pluginName: String): Boolean =
        runtime.pluginDependencies
            .any { owner -> owner.equals(pluginName, ignoreCase = true) }

    private fun clearGeneration(
        current: ManagedProjectGeneration,
        report: MutableScriptProjectReport
    ): Int {
        val count = current.sourceNames.size
        try {
            current.runtime.deactivate()
        } catch (exception: Throwable) {
            diagnostics.lifecycleFailure(
                current.project,
                ScriptLifecycleFailurePhase.DISABLE,
                "unload disable",
                exception,
                report
            )
        } finally {
            retirement.dispose(current, "unload cleanup", report)
            active.compareAndSet(current, null)
            current.runtime.retire()
        }
        return count
    }

    internal fun generationSnapshot(): ScriptProjectGenerationSnapshot = activeView.snapshot()
}


internal enum class FrozenGenerationAbortAction {
    RESTORE,
    KEEP_FROZEN
}

/** Cancellation is destructive: a frozen generation may only reopen before it starts. */
internal fun frozenGenerationAbortAction(
    trackedWorkCancellationStarted: Boolean
): FrozenGenerationAbortAction =
    if (trackedWorkCancellationStarted) {
        FrozenGenerationAbortAction.KEEP_FROZEN
    } else {
        FrozenGenerationAbortAction.RESTORE
    }

internal enum class FrozenGenerationClearPreparation {
    FREEZE,
    RETRY_DRAIN,
    REJECT
}

internal fun clearPreparation(
    state: ScriptExecutionGate.State
): FrozenGenerationClearPreparation =
    when (state) {
        ScriptExecutionGate.State.ACTIVE -> FrozenGenerationClearPreparation.FREEZE
        ScriptExecutionGate.State.SWAPPING -> FrozenGenerationClearPreparation.RETRY_DRAIN
        ScriptExecutionGate.State.STAGED,
        ScriptExecutionGate.State.RETIRED -> FrozenGenerationClearPreparation.REJECT
    }

internal fun <T : Any> resolveScriptProjectLoadOutcome(
    activated: Boolean,
    expected: T?,
    current: T?,
    generation: ScriptProjectGenerationSnapshot
): ScriptProjectLoadOutcome =
    when {
        activated -> ScriptProjectLoadOutcome.ACTIVATED
        expected != null && current === expected && generation.acceptsCallbacks ->
            ScriptProjectLoadOutcome.REJECTED_PREVIOUS_ACTIVE
        current != null && generation.acceptsCallbacks ->
            ScriptProjectLoadOutcome.REJECTED_ACTIVE_CHANGED
        else -> ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE
    }

internal suspend fun <T> withOwnershipCleanup(
    cleanup: suspend () -> Unit,
    block: suspend () -> T
): T = try {
    block()
} finally {
    cleanup()
}
