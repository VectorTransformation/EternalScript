package eternalScript.core.script.generation

import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.project.KotlinProjectBackend
import eternalScript.core.script.project.ScriptProjectBackend
import eternalScript.core.script.project.ScriptProjectSource
import eternalScript.core.script.project.ScriptProjectRuntime
import eternalScript.core.the.Root
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.script.experimental.api.valueOrNull

/**
 * Owns the transactional lifecycle of one active script generation.
 * Compilation and evaluation are delegated to the project backend, while
 * this coordinator controls epoch fences, ownership, activation, replacement,
 * rollback, and disposal.
 */
internal class ScriptGenerationCoordinator {
    private val backend: ScriptProjectBackend = KotlinProjectBackend()
    private val diagnostics = GenerationDiagnostics()
    private val active = AtomicReference<ManagedProjectGeneration?>()
    private val activeView = ActiveGenerationView(active::get)
    private val lifecycle = GenerationCoordinatorLifecycle()
    private val pendingCandidates = GenerationOwnershipRegistry<ManagedProjectGeneration>()
    private val pendingRetirements = GenerationOwnershipRegistry<ManagedProjectGeneration>()
    private val invalidatedGenerations = ConcurrentHashMap.newKeySet<ManagedProjectGeneration>()
    private val environmentFence = ScriptEnvironmentFence()

    internal fun open() {
        lifecycle.open()
    }

    internal fun close() {
        lifecycle.close()
    }

    internal fun invalidateEnvironment() {
        environmentFence.invalidate()
    }

    fun stop() {
        close()
        val current = active.getAndSet(null)
        val candidates = pendingCandidates.claimAll()
        val retirements = pendingRetirements.claimAll()
        if (current == null && candidates.isEmpty() && retirements.isEmpty()) return
        val report = MutableScriptProjectReport(logSummaries = true)
        runBlocking {
            current?.let { shutdownGeneration(it, report) }
            candidates.forEach { candidate ->
                shutdownGeneration(candidate, report)
            }
            retirements.forEach { retirement ->
                shutdownGeneration(retirement, report)
            }
        }
    }

    private fun openEpoch(): Long? = lifecycle.openEpoch()

    private fun isOpen(epoch: Long): Boolean =
        lifecycle.accepts(epoch)

    private suspend fun shutdownGeneration(
        current: ManagedProjectGeneration,
        report: MutableScriptProjectReport
    ) {
        val frozen = current.runtime.tryFreeze()
        val callbacksDrained = if (
            frozen ||
            current.runtime.state == ScriptExecutionGate.State.SWAPPING
        ) {
            awaitDrain(current.runtime, GENERATION_SHUTDOWN_DRAIN_ATTEMPTS)
        } else {
            current.runtime.isDrained
        }
        val trackedWorkDrained = current.runtime.cancelTrackedWorkAndJoin(
            TRACKED_WORK_SHUTDOWN_TIMEOUT_MILLIS
        )
        if (!callbacksDrained || !trackedWorkDrained) {
            Root.INSTANCE.logger.warning(
                "Script generation shutdown exceeded its drain deadline " +
                    "(callbacks=$callbacksDrained, trackedWork=$trackedWorkDrained); " +
                    "forced cleanup will continue because the plugin is disabling."
            )
        }

        current.runtime.retire()
        try {
            current.runtime.deactivate()
        } catch (exception: Throwable) {
            diagnostics.lifecycleFailure(
                current.project,
                ScriptLifecycleFailurePhase.DISABLE,
                "shutdown disable",
                exception,
                report
            )
        } finally {
            dispose(current, "shutdown cleanup", report)
        }
    }

    private fun compile(project: ScriptProjectSource) = backend.compile(project)

    internal suspend fun load(project: ScriptProjectSource): ScriptProjectLoadResult {
        val expected = active.get()
        val previousGeneration = activeView.snapshot()
        val report = MutableScriptProjectReport()
        val activated = loadTransaction(project, expected, report)
        val current = active.get()
        val generation = activeView.snapshot()
        val outcome = when {
            activated -> ScriptProjectLoadOutcome.ACTIVATED
            expected != null && current === expected && generation.exists ->
                ScriptProjectLoadOutcome.REJECTED_PREVIOUS_ACTIVE
            current != null && generation.exists ->
                ScriptProjectLoadOutcome.REJECTED_ACTIVE_CHANGED
            else -> ScriptProjectLoadOutcome.REJECTED_NO_ACTIVE
        }
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
        val environment = environmentFence.snapshot()
        val compilation = compile(project)
        val compiled = compilation.valueOrNull()

        diagnostics.report(
            project,
            compilation,
            GenerationDiagnosticPhase.COMPILATION,
            report
        )

        if (!canCommit(epoch, environment) || active.get() !== expected) return false
        if (compiled == null) return false

        return withContext(NonCancellable) {
            val replacement = Root.global stage@{
                if (!canCommit(epoch, environment) || active.get() !== expected) return@stage null
                val evaluation = backend.evaluate(compiled)
                diagnostics.report(
                    project,
                    evaluation,
                    GenerationDiagnosticPhase.EVALUATION,
                    report
                )

                val runtime = evaluation.valueOrNull()?.returnValue?.scriptInstance
                    as? ScriptProjectRuntime
                    ?: return@stage null
                var data: ScriptGeneration? = null
                data = try {
                    runtime.transfer().also { generation ->
                        generation.mapRuntimeExceptions(project)
                    }
                } catch (exception: Throwable) {
                    runCatching {
                        data?.dispose() ?: runtime.close()
                    }
                        .exceptionOrNull()
                        ?.let(exception::addSuppressed)
                    throw exception
                }
                try {
                    ManagedProjectGeneration(project, data).also { candidate ->
                        check(pendingCandidates.transfer(candidate)) {
                            "The staged script candidate already has an owner."
                        }
                    }
                } catch (exception: Throwable) {
                    runCatching(data::dispose)
                        .exceptionOrNull()
                        ?.let(exception::addSuppressed)
                    throw exception
                }
            } ?: return@withContext false

            if (!canCommit(epoch, environment) || active.get() !== expected) {
                Root.global {
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
            Root.global {
                discard(replacement, report)
            }
            return false
        }

        var activationAttempted = false
        var activationFailure: Throwable? = null
        val activated = try {
            Root.global {
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
            Root.global {
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
            Root.global {
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
        try {
            if (!canCommit(epoch, environment) || !freeze(current, epoch)) {
                return false
            }
            ownsFrozenCurrent = true

            val trackedWorkDrained = current.runtime.cancelTrackedWorkAndJoin(
                TRACKED_WORK_DRAIN_TIMEOUT_MILLIS
            )
            if (!trackedWorkDrained) {
                Root.INSTANCE.logger.warning(
                    "Tracked script work did not stop within ${TRACKED_WORK_DRAIN_TIMEOUT_MILLIS}ms; " +
                        "the generation replacement was aborted."
                )
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
                Root.global {
                    discard(replacement, report)
                }
            }
            if (
                ownsFrozenCurrent &&
                isOpen(epoch) &&
                active.get() === current &&
                current !in invalidatedGenerations
            ) {
                current.runtime.restore()
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
            if (awaitDrain(generation.runtime, GENERATION_FREEZE_ATTEMPTS)) {
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

    private suspend fun awaitDrain(
        scriptData: ScriptGeneration,
        attempts: Int
    ): Boolean {
        repeat(attempts) {
            if (scriptData.isDrained) {
                return true
            }
            delay(GENERATION_FREEZE_RETRY_MILLIS)
        }
        return scriptData.isDrained
    }

    private suspend fun replaceFrozen(
        current: ManagedProjectGeneration,
        replacement: ManagedProjectGeneration,
        epoch: Long,
        environment: Long,
        report: MutableScriptProjectReport
    ): Boolean {
        val deactivated = try {
            Root.global {
                if (!canCommit(epoch, environment) || active.get() !== current) {
                    false
                } else {
                    current.runtime.deactivate()
                    true
                }
            }
        } catch (exception: Throwable) {
            Root.global {
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
            Root.global {
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
            Root.global {
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
            Root.global {
                discard(replacement, report)
            }
            restore(current, epoch, report)
            return false
        }

        val published = try {
            Root.global {
                if (
                    !canCommit(epoch, environment) ||
                    !active.compareAndSet(current, replacement)
                ) {
                    false
                } else if (
                    !canCommit(epoch, environment) ||
                    !replacement.runtime.publish()
                ) {
                    active.compareAndSet(replacement, current)
                    false
                } else {
                    check(pendingRetirements.transfer(current)) {
                        "The previous script generation already has a retirement owner."
                    }
                    check(pendingCandidates.claim(replacement)) {
                        "The published script candidate lost transaction ownership."
                    }
                    true
                }
            }
        } catch (exception: Throwable) {
            active.compareAndSet(replacement, current)
            pendingRetirements.claim(current)
            Root.global {
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

        Root.global {
            if (!pendingRetirements.claim(current)) return@global
            current.runtime.retire()
            dispose(current, "retire cleanup", report)
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
            Root.global {
                if (generation in invalidatedGenerations) return@global false
                if (!isOpen(epoch) || active.get() !== generation) {
                    generation.runtime.retire()
                    dispose(generation, "restore cleanup", report)
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
                Root.global {
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
                Root.INSTANCE.logger.warning(
                    "Restored script-generation work did not stop within " +
                        "${TRACKED_WORK_DRAIN_TIMEOUT_MILLIS}ms; forced cleanup will continue."
                )
            }
            cleanupFailures.forEach(exception::addSuppressed)

            Root.global {
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
                dispose(generation, "restore cleanup", report)
                active.compareAndSet(generation, null)
            }
            false
        }
    }

    private fun canCommit(epoch: Long, environment: Long): Boolean =
        isOpen(epoch) && environmentFence.accepts(environment)

    private suspend fun cleanupFailedActivation(
        generation: ManagedProjectGeneration,
        report: MutableScriptProjectReport
    ) {
        val runtime = generation.runtime
        runtime.retire()
        val trackedWorkDrained = try {
            runtime.cancelTrackedWorkAndJoin(TRACKED_WORK_DRAIN_TIMEOUT_MILLIS)
        } catch (exception: Throwable) {
            Root.global {
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
            Root.INSTANCE.logger.warning(
                "Failed script-generation work did not stop within " +
                    "${TRACKED_WORK_DRAIN_TIMEOUT_MILLIS}ms; forced cleanup will continue."
            )
        }
        Root.global {
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
                dispose(generation, "failed activation cleanup", report)
            }
        }
    }

    private fun discard(
        generation: ManagedProjectGeneration,
        report: MutableScriptProjectReport
    ) {
        if (!pendingCandidates.claim(generation)) return
        generation.runtime.retire()
        dispose(generation, "discard cleanup", report)
    }

    private fun dispose(
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
            invalidatedGenerations.remove(generation)
        }
    }

    internal suspend fun check(project: ScriptProjectSource): ScriptProjectCheckResult {
        val result = compile(project)
        val report = MutableScriptProjectReport()
        diagnostics.report(
            project,
            result,
            GenerationDiagnosticPhase.COMPILATION,
            report
        )
        return ScriptProjectCheckResult(
            outcome = if (result.valueOrNull() != null) {
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
        try {
            if (!freeze(current, epoch)) return null
            ownsFrozenCurrent = true

            val trackedWorkDrained = current.runtime.cancelTrackedWorkAndJoin(
                TRACKED_WORK_DRAIN_TIMEOUT_MILLIS
            )
            if (!trackedWorkDrained) {
                Root.INSTANCE.logger.warning(
                    "Tracked script work did not stop within ${TRACKED_WORK_DRAIN_TIMEOUT_MILLIS}ms; " +
                        "the generation unload was aborted."
                )
                return null
            }

            return Root.global clear@{
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
                current.runtime.restore()
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

        val callbacksDrained = awaitDrain(
            current.runtime,
            GENERATION_SHUTDOWN_DRAIN_ATTEMPTS
        )
        val trackedWorkDrained = current.runtime.cancelTrackedWorkAndJoin(
            TRACKED_WORK_SHUTDOWN_TIMEOUT_MILLIS
        )
        if (!callbacksDrained || !trackedWorkDrained) {
            Root.INSTANCE.logger.warning(
                "A plugin used by the active script generation was disabled, but " +
                    "generation drain exceeded its deadline " +
                    "(callbacks=$callbacksDrained, trackedWork=$trackedWorkDrained). " +
                    "Cleanup is deferred with new script entries blocked."
            )
            return null
        }

        val report = MutableScriptProjectReport(logSummaries = true)
        return Root.global clear@{
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
            dispose(current, "unload cleanup", report)
            active.compareAndSet(current, null)
            current.runtime.retire()
        }
        return count
    }

    internal fun generationSnapshot(): ScriptProjectGenerationSnapshot = activeView.snapshot()
}

private const val GENERATION_FREEZE_ATTEMPTS = 50
private const val GENERATION_SHUTDOWN_DRAIN_ATTEMPTS = 1_000
private const val GENERATION_FREEZE_RETRY_MILLIS = 10L
private const val TRACKED_WORK_DRAIN_TIMEOUT_MILLIS = 2_000L
private const val TRACKED_WORK_SHUTDOWN_TIMEOUT_MILLIS = 10_000L
