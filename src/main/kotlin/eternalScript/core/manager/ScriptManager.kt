package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.core.data.Config
import eternalScript.core.extension.unwrap
import eternalScript.core.extension.wrap
import eternalScript.core.script.Script
import eternalScript.core.script.data.ScriptData
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.project.KotlinProjectBackend
import eternalScript.core.script.project.PROJECT_SCRIPT_NAME
import eternalScript.core.script.project.ScriptProjectBackend
import eternalScript.core.script.project.ScriptProjectSource
import eternalScript.core.script.project.failureDiagnostics
import eternalScript.core.script.project.remapRuntimeStackTrace
import eternalScript.core.script.project.runtimePosition
import eternalScript.core.the.Root
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bukkit.command.CommandSender
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.valueOrNull

private data class ScriptProjectGeneration(
    val project: ScriptProjectSource,
    val data: ScriptData,
    val scriptNames: Set<String> = project.files.mapTo(linkedSetOf()) { it.name }
)

object ScriptManager : Manager {
    private val backend: ScriptProjectBackend = KotlinProjectBackend()
    private val active = AtomicReference<ScriptProjectGeneration?>()
    private val lifecycle = ScriptManagerLifecycle()
    private val pendingCandidates = GenerationOwnershipRegistry<ScriptProjectGeneration>()
    private val pendingRetirements = GenerationOwnershipRegistry<ScriptProjectGeneration>()

    internal fun open() {
        lifecycle.open()
    }

    internal fun close() {
        lifecycle.close()
    }

    override fun unregister() {
        close()
        val current = active.getAndSet(null)
        val candidates = pendingCandidates.claimAll()
        val retirements = pendingRetirements.claimAll()
        if (current == null && candidates.isEmpty() && retirements.isEmpty()) return
        runBlocking {
            current?.let { shutdownGeneration(it) }
            candidates.forEach { candidate ->
                shutdownGeneration(candidate)
            }
            retirements.forEach { retirement ->
                shutdownGeneration(retirement)
            }
        }
    }

    private fun openEpoch(): Long? = lifecycle.openEpoch()

    private fun isOpen(epoch: Long): Boolean =
        lifecycle.accepts(epoch)

    private suspend fun shutdownGeneration(current: ScriptProjectGeneration) {
        val frozen = current.data.tryFreeze()
        val callbacksDrained = if (
            frozen ||
            current.data.executionGate.state == ScriptExecutionGate.State.SWAPPING
        ) {
            awaitDrain(current.data, GENERATION_SHUTDOWN_DRAIN_ATTEMPTS)
        } else {
            current.data.isDrained
        }
        val trackedWorkDrained = current.data.cancelTrackedWorkAndJoin(
            TRACKED_WORK_SHUTDOWN_TIMEOUT_MILLIS
        )
        if (!callbacksDrained || !trackedWorkDrained) {
            Root.INSTANCE.logger.warning(
                "Script generation shutdown exceeded its drain deadline " +
                    "(callbacks=$callbacksDrained, trackedWork=$trackedWorkDrained); " +
                    "forced cleanup will continue because the plugin is disabling."
            )
        }

        current.data.retire()
        try {
            current.data.deactivate()
        } catch (exception: Throwable) {
            lifecycleFailure(current.project, "disable", exception, null)
        } finally {
            dispose(current, null, "shutdown cleanup")
        }
    }

    private fun compile(project: ScriptProjectSource) = backend.compile(project)

    private fun report(
        project: ScriptProjectSource,
        result: ResultWithDiagnostics<*>,
        sender: CommandSender?
    ): Int {
        val errors = project.failureDiagnostics(result)

        errors.forEach { diagnostic ->
            LangManager.sendMessage(
                sender,
                "script.compile.error",
                args = listOf(
                    diagnostic.sourceName.wrap(),
                    diagnostic.line?.toString() ?: "-",
                    diagnostic.column?.toString() ?: "-",
                    diagnostic.report.message
                )
            )
            if (ConfigManager.value(Config.DEBUG)) {
                diagnostic.report.exception?.let { exception ->
                    val message = LangManager.translatable("script.compile.exception")
                        .format(diagnostic.sourceName.wrap())
                    Root.INSTANCE.logger.log(Level.WARNING, message, exception)
                }
            }
        }
        return errors.size
    }

    internal suspend fun load(project: ScriptProjectSource, sender: CommandSender? = null): Boolean {
        val epoch = openEpoch() ?: return false
        val expected = active.get()
        val compilation = compile(project)
        val compiled = compilation.valueOrNull()

        if (!isOpen(epoch) || active.get() !== expected) return false
        if (compiled == null) {
            Root.global {
                report(project, compilation, sender)
            }
            return false
        }

        return withContext(NonCancellable) {
            val replacement = Root.global stage@{
                if (!isOpen(epoch) || active.get() !== expected) return@stage null
                report(project, compilation, sender)
                val evaluation = backend.evaluate(compiled)
                report(project, evaluation, sender)

                val script = evaluation.valueOrNull()?.returnValue?.scriptInstance as? Script
                    ?: return@stage null
                val data = try {
                    ScriptData(script).also { scriptData ->
                        scriptData.mapRuntimeExceptions(project)
                    }
                } catch (exception: Throwable) {
                    runCatching(script::disposeRuntime)
                        .exceptionOrNull()
                        ?.let(exception::addSuppressed)
                    throw exception
                }
                ScriptProjectGeneration(project, data).also { candidate ->
                    check(pendingCandidates.transfer(candidate)) {
                        "The staged script candidate already has an owner."
                    }
                }
            } ?: return@withContext false

            if (!isOpen(epoch) || active.get() !== expected) {
                Root.global {
                    discard(replacement, sender)
                }
                return@withContext false
            }

            if (expected == null) {
                activateInitial(replacement, sender, epoch)
            } else {
                replace(expected, replacement, sender, epoch)
            }
        }
    }

    private suspend fun activateInitial(
        replacement: ScriptProjectGeneration,
        sender: CommandSender?,
        epoch: Long
    ): Boolean {
        if (!isOpen(epoch)) {
            Root.global {
                discard(replacement, sender)
            }
            return false
        }

        var activationAttempted = false
        var activationFailure: Throwable? = null
        val activated = try {
            Root.global {
                if (!isOpen(epoch)) {
                    false
                } else {
                    activationAttempted = true
                    replacement.data.activate()
                    if (!isOpen(epoch) || !active.compareAndSet(null, replacement)) {
                        false
                    } else if (!isOpen(epoch) || !replacement.data.publish()) {
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
                lifecycleFailure(replacement.project, "enable", exception, sender)
            }
        }
        if (activationAttempted) {
            cleanupFailedActivation(replacement, sender)
        } else {
            Root.global {
                discard(replacement, sender)
            }
        }
        return false
    }

    private suspend fun replace(
        current: ScriptProjectGeneration,
        replacement: ScriptProjectGeneration,
        sender: CommandSender?,
        epoch: Long
    ): Boolean {
        var ownsReplacement = true
        var ownsFrozenCurrent = false
        try {
            if (!freeze(current, epoch)) {
                if (isOpen(epoch) && active.get() === current && current.data.isActive) {
                    Root.global {
                        LangManager.sendMessage(sender, "script.operation.busy")
                    }
                }
                return false
            }
            ownsFrozenCurrent = true

            val trackedWorkDrained = current.data.cancelTrackedWorkAndJoin(
                TRACKED_WORK_DRAIN_TIMEOUT_MILLIS
            )
            if (!trackedWorkDrained) {
                Root.INSTANCE.logger.warning(
                    "Tracked script work did not stop within ${TRACKED_WORK_DRAIN_TIMEOUT_MILLIS}ms; " +
                        "the generation replacement was aborted."
                )
                Root.global {
                    LangManager.sendMessage(sender, "script.operation.busy")
                }
                return false
            }

            if (!isOpen(epoch) || active.get() !== current) return false
            val result = replaceFrozen(current, replacement, sender, epoch)
            ownsReplacement = false
            ownsFrozenCurrent = false
            return result
        } finally {
            if (ownsReplacement) {
                Root.global {
                    discard(replacement, sender)
                }
            }
            if (
                ownsFrozenCurrent &&
                isOpen(epoch) &&
                active.get() === current
            ) {
                current.data.restore()
            }
        }
    }

    private suspend fun freeze(
        generation: ScriptProjectGeneration,
        epoch: Long
    ): Boolean {
        if (!isOpen(epoch) || active.get() !== generation) return false
        if (!generation.data.tryFreeze()) return false

        var ownsFrozenGeneration = true
        try {
            if (awaitDrain(generation.data, GENERATION_FREEZE_ATTEMPTS)) {
                ownsFrozenGeneration = false
                return true
            }
            return false
        } finally {
            if (
                ownsFrozenGeneration &&
                isOpen(epoch) &&
                active.get() === generation
            ) {
                generation.data.restore()
            }
        }
    }

    private suspend fun awaitDrain(
        scriptData: ScriptData,
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
        current: ScriptProjectGeneration,
        replacement: ScriptProjectGeneration,
        sender: CommandSender?,
        epoch: Long
    ): Boolean {
        val deactivated = try {
            Root.global {
                if (!isOpen(epoch) || active.get() !== current) {
                    false
                } else {
                    current.data.deactivate()
                    true
                }
            }
        } catch (exception: Throwable) {
            Root.global {
                lifecycleFailure(current.project, "disable", exception, sender)
                discard(replacement, sender)
                restore(current, sender, epoch)
            }
            return false
        }
        if (!deactivated) return false

        val activationAttempted = try {
            Root.global {
                if (!isOpen(epoch) || active.get() !== current) {
                    false
                } else {
                    // This is the final closed/epoch fence before candidate
                    // activation. Both close() and lifecycle callbacks execute
                    // on the global thread, so no shutdown commit can cross it.
                    replacement.data.activate()
                    true
                }
            }
        } catch (exception: Throwable) {
            Root.global {
                lifecycleFailure(replacement.project, "enable", exception, sender)
            }
            cleanupFailedActivation(replacement, sender)
            Root.global {
                restore(current, sender, epoch)
            }
            return false
        }
        if (!activationAttempted) {
            Root.global {
                discard(replacement, sender)
                restore(current, sender, epoch)
            }
            return false
        }

        val published = try {
            Root.global {
                if (!isOpen(epoch) || !active.compareAndSet(current, replacement)) {
                    false
                } else if (!isOpen(epoch) || !replacement.data.publish()) {
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
                lifecycleFailure(replacement.project, "publish", exception, sender)
            }
            false
        }

        if (!published) {
            cleanupFailedActivation(replacement, sender)
            Root.global {
                restore(current, sender, epoch)
            }
            return false
        }

        Root.global {
            if (!pendingRetirements.claim(current)) return@global
            current.data.retire()
            dispose(current, sender, "retire cleanup")
        }
        return true
    }

    private fun restore(
        generation: ScriptProjectGeneration,
        sender: CommandSender?,
        epoch: Long
    ): Boolean {
        if (!isOpen(epoch) || active.get() !== generation) {
            generation.data.retire()
            dispose(generation, sender, "restore cleanup")
            active.compareAndSet(generation, null)
            return false
        }
        return try {
            generation.data.activate()
            if (generation.data.restore()) {
                true
            } else {
                error("The previous script generation could not leave the swapping state.")
            }
        } catch (exception: Throwable) {
            lifecycleFailure(generation.project, "restore", exception, sender)
            generation.data.retire()
            dispose(generation, sender, "restore cleanup")
            active.compareAndSet(generation, null)
            false
        }
    }

    private suspend fun cleanupFailedActivation(
        generation: ScriptProjectGeneration,
        sender: CommandSender?
    ) {
        val scriptData = generation.data
        scriptData.retire()
        val trackedWorkDrained = try {
            scriptData.cancelTrackedWorkAndJoin(TRACKED_WORK_DRAIN_TIMEOUT_MILLIS)
        } catch (exception: Throwable) {
            Root.global {
                lifecycleFailure(generation.project, "cleanup drain", exception, sender)
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
                scriptData.deactivate()
            } catch (exception: Throwable) {
                lifecycleFailure(generation.project, "cleanup", exception, sender)
            } finally {
                dispose(generation, sender, "failed activation cleanup")
            }
        }
    }

    private fun discard(
        generation: ScriptProjectGeneration,
        sender: CommandSender?
    ) {
        if (!pendingCandidates.claim(generation)) return
        generation.data.retire()
        dispose(generation, sender, "discard cleanup")
    }

    private fun dispose(
        generation: ScriptProjectGeneration,
        sender: CommandSender?,
        phase: String
    ) {
        try {
            generation.data.dispose()
        } catch (exception: Throwable) {
            lifecycleFailure(generation.project, phase, exception, sender)
        }
    }

    private fun lifecycleFailure(
        project: ScriptProjectSource,
        phase: String,
        exception: Throwable,
        sender: CommandSender?
    ) {
        val position = project.runtimePosition(exception)
        val sourceName = position?.sourceName ?: PROJECT_SCRIPT_NAME
        val line = position?.line?.toString() ?: "-"
        project.remapRuntimeStackTrace(exception)
        val fallback =
            "EternalScript project lifecycle failed at $sourceName:$line during $phase."
        runCatching {
            LangManager.sendMessage(
                sender,
                "script.lifecycle.error",
                args = listOf(
                    sourceName.wrap(),
                    line,
                    phase,
                    exception.message ?: exception.javaClass.simpleName
                )
            )
        }.onFailure { diagnosticFailure ->
            runCatching {
                Root.INSTANCE.logger.log(Level.WARNING, fallback, diagnosticFailure)
            }
        }
        if (ConfigManager.value(Config.DEBUG)) {
            val message = runCatching {
                LangManager.translatable("script.lifecycle.exception")
                    .format(sourceName.wrap(), line, phase)
            }.getOrDefault(fallback)
            runCatching {
                Root.INSTANCE.logger.log(Level.WARNING, message, exception)
            }
        }
    }

    internal suspend fun check(
        project: ScriptProjectSource,
        sender: CommandSender? = null,
        announceName: String? = null
    ): Boolean {
        val result = compile(project)
        return Root.global {
            val errorCount = report(project, result, sender)
            val success = result.valueOrNull() != null
            if (announceName != null) {
                val key = if (success) "script.check.passed" else "script.check.failed"
                val args = if (success) {
                    listOf(announceName.wrap())
                } else {
                    listOf(announceName.wrap(), errorCount.toString())
                }
                LangManager.sendMessage(sender, key, args = args)
            }
            success
        }
    }

    internal suspend fun clearNow(): Int? {
        val epoch = openEpoch() ?: return null
        val current = active.get() ?: return 0
        var ownsFrozenCurrent = false
        try {
            if (!freeze(current, epoch)) return null
            ownsFrozenCurrent = true

            val trackedWorkDrained = current.data.cancelTrackedWorkAndJoin(
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
                val count = clearGeneration(current)
                ownsFrozenCurrent = false
                count
            }
        } finally {
            if (
                ownsFrozenCurrent &&
                isOpen(epoch) &&
                active.get() === current
            ) {
                current.data.restore()
            }
        }
    }

    private fun clearGeneration(current: ScriptProjectGeneration): Int {
        val count = current.scriptNames.size
        try {
            current.data.deactivate()
        } catch (exception: Throwable) {
            lifecycleFailure(current.project, "disable", exception, null)
        } finally {
            dispose(current, null, "unload cleanup")
            active.compareAndSet(current, null)
            current.data.retire()
        }
        return count
    }

    fun remove(key: String, sender: CommandSender? = null, silent: Boolean = false): Boolean {
        val name = key.unwrap()
        if (name !in scripts()) {
            if (!silent) {
                LangManager.sendMessage(sender, "script.error.not_loaded", args = listOf(name.wrap()))
            }
            return false
        }

        if (!silent) {
            LangManager.sendMessage(sender, "script.unload.project_only")
        }
        return false
    }

    fun scripts(): Set<String> {
        val generation = active.get() ?: return emptySet()
        return if (generation.data.isActive) {
            generation.scriptNames.toSet()
        } else {
            emptySet()
        }
    }

    @Deprecated(
        message = "All source files now share one ScriptData generation. Use functions() or call() when possible."
    )
    fun script(script: String): ScriptData? {
        val name = script.unwrap()
        val generation = active.get() ?: return null
        if (name !in generation.scriptNames || !generation.data.isActive) return null
        return generation.data
    }

    private fun <T> withScript(
        script: String,
        block: (ScriptData) -> T
    ): T? {
        val name = script.unwrap()
        val generation = active.get() ?: return null
        if (name !in generation.scriptNames) return null
        return generation.data.withActive {
            block(generation.data)
        }
    }

    fun functions(script: String) =
        withScript(script) { data ->
            data.functions()
        } ?: emptyList()

    fun call(script: String, function: String, vararg args: Any?) = withScript(script) { data ->
        data.call(function.unwrap(), *args)
    }

    fun scriptList(sender: CommandSender? = null) {
        val scripts = scripts().sorted()
        if (scripts.isEmpty()) {
            LangManager.sendMessage(sender, "script.list.empty")
            return
        }
        LangManager.sendMessage(sender, "script.list.header", args = listOf(scripts.size.toString()))
        scripts.map(String::wrap).forEach { script ->
            LangManager.sendMessage(sender, "script.list.entry", args = listOf(script))
        }
    }

}

internal class ScriptManagerLifecycle {
    private data class State(
        val epoch: Long,
        val open: Boolean
    )

    private val state = AtomicReference(State(epoch = 0, open = false))

    fun open(): Long =
        state.updateAndGet { current ->
            if (current.open) current else State(current.epoch + 1, true)
        }.epoch

    fun close(): Long =
        state.updateAndGet { current ->
            if (!current.open) current else State(current.epoch + 1, false)
        }.epoch

    fun openEpoch(): Long? =
        state.get().let { current ->
            current.epoch.takeIf { current.open }
        }

    fun accepts(epoch: Long): Boolean =
        state.get().let { current ->
            current.open && current.epoch == epoch
        }
}

internal class GenerationOwnershipRegistry<T : Any> {
    private val generations = ConcurrentHashMap.newKeySet<T>()

    fun transfer(generation: T): Boolean =
        generations.add(generation)

    fun claim(generation: T): Boolean =
        generations.remove(generation)

    fun claimAll(): List<T> =
        generations.toList().filter(generations::remove)
}

private const val GENERATION_FREEZE_ATTEMPTS = 50
private const val GENERATION_SHUTDOWN_DRAIN_ATTEMPTS = 1_000
private const val GENERATION_FREEZE_RETRY_MILLIS = 10L
private const val TRACKED_WORK_DRAIN_TIMEOUT_MILLIS = 2_000L
private const val TRACKED_WORK_SHUTDOWN_TIMEOUT_MILLIS = 10_000L
