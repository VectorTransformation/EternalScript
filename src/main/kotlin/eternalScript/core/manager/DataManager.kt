package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.extension.wrap
import eternalScript.core.script.data.ScriptSuffix
import eternalScript.core.script.definition.ScriptCompilationCache
import eternalScript.core.script.project.PROJECT_SCRIPT_NAME
import eternalScript.core.script.project.legacyScriptWarning
import eternalScript.core.script.project.runtimeScriptProjectRepository
import eternalScript.core.the.GlobalTaskOwner
import eternalScript.core.the.Root
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.logging.Level
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object DataManager : Manager {
    private val operationLock = Any()
    private var operation: ScriptProjectOperation? = null
    private val lifecycle = DataManagerLifecycle()
    private val scriptRepository by lazy(::runtimeScriptProjectRepository)

    override fun register() {
        synchronized(operationLock) {
            lifecycle.open()
        }
        ScriptManager.open()
        makeAll()
        compile()
    }

    fun shutdown() {
        val current = synchronized(operationLock) {
            lifecycle.close()
            ScriptManager.close()
            operation.also { activeOperation ->
                operation = null
                activeOperation?.owner?.let(Root::beginGlobalTaskOwnerShutdown)
            }
        }
        Root.shutdown(current?.owner)
        current?.job?.cancel()
        val operationStopped = try {
            awaitOperationShutdown(
                operation = current?.job,
                timeoutMillis = OPERATION_SHUTDOWN_TIMEOUT_MILLIS,
                isGlobalThread = Bukkit.isGlobalTickThread(),
                pumpGlobalTasks = {
                    current?.owner?.let(Root::drainPendingGlobalTasks)
                }
            )
        } finally {
            current?.owner?.let(Root::closeGlobalTaskOwner)
        }
        if (!operationStopped) {
            Root.INSTANCE.logger.warning(
                "The active script project operation did not stop within " +
                    "${OPERATION_SHUTDOWN_TIMEOUT_MILLIS}ms; shutdown will continue " +
                    "with generation commits fenced."
            )
        }
    }

    fun makeAll() {
        listOf(
            Resource.DATA_FOLDER,
            Resource.LIBS,
            Resource.CACHE
        ).forEach(Resource::make)

        saveResource(Resource.SCRIPTS, *ScriptSuffix.SCRIPT.suffix)

        listOf(
            Resource.LANG
        ).forEach { resource ->
            saveResource(resource, *ScriptSuffix.LANG.suffix)
        }

        Root.register(ReloadManager)
    }

    fun saveResource(resource: Resource, vararg extension: String) {
        if (!resource.exists()) {
            val jarPath = javaClass.protectionDomain.codeSource.location.path
            val fileName = resource.file.nameWithoutExtension
            ZipFile(jarPath).use { jar ->
                jar.entries()
                    .asSequence()
                    .map(ZipEntry::getName)
                    .filter { name ->
                        name.startsWith(fileName) && extension.any(name::endsWith)
                    }.forEach { name ->
                        Root.INSTANCE.saveResource(name, false)
                    }
            }
        }
    }

    fun compile(sender: CommandSender? = null) {
        if (sender == null) {
            startOperation(null) { session ->
                snapshotWithLegacyWarning()?.let { project ->
                    if (lifecycle.accepts(session)) {
                        ScriptManager.load(project)
                    }
                }
            }
        } else {
            reloadAll(sender)
        }
    }

    fun reloadAll(sender: CommandSender) {
        startOperation(sender) { session ->
            val project = snapshotWithLegacyWarning()
            if (!lifecycle.accepts(session)) return@startOperation
            if (project == null) {
                Root.global {
                    LangManager.sendMessage(sender, "script.error.empty_project")
                }
                return@startOperation
            }
            val total = project.files.size
            Root.global {
                LangManager.sendMessage(sender, "script.reload.all_started", args = listOf(total.toString()))
            }

            if (!lifecycle.accepts(session)) return@startOperation
            val success = ScriptManager.load(project, sender)
            val passed = if (success) total else 0
            Root.global {
                LangManager.sendMessage(
                    sender,
                    "script.reload.all_completed",
                    args = listOf(total.toString(), passed.toString(), (total - passed).toString())
                )
            }
        }
    }

    fun reloadScript(script: String, sender: CommandSender) {
        startOperation(sender) { session ->
            val project = snapshotWithLegacyWarning()
            if (!lifecycle.accepts(session)) return@startOperation
            val available = project?.files?.mapTo(hashSetOf()) { it.name }.orEmpty()
            if (script !in available && script !in ScriptManager.scripts()) {
                Root.global {
                    LangManager.sendMessage(sender, "script.error.not_found", args = listOf(script.wrap()))
                }
                return@startOperation
            }

            Root.global {
                LangManager.sendMessage(sender, "script.reload.one_started", args = listOf(script.wrap()))
            }
            if (!lifecycle.accepts(session)) return@startOperation
            val success = project?.let { ScriptManager.load(it, sender) } ?: false
            Root.global {
                val key = if (success) "script.reload.completed" else "script.reload.failed"
                LangManager.sendMessage(sender, key, args = listOf(script.wrap()))
            }
        }
    }

    fun checkAll(sender: CommandSender? = null) {
        startOperation(sender) { session ->
            val project = snapshotWithLegacyWarning()
            if (!lifecycle.accepts(session)) return@startOperation
            val total = project?.files?.size ?: 0
            Root.global {
                LangManager.sendMessage(sender, "script.check.all_started", args = listOf(total.toString()))
            }
            if (!lifecycle.accepts(session)) return@startOperation
            val success = project == null || ScriptManager.check(project, sender)
            val passed = if (success) total else 0
            Root.global {
                LangManager.sendMessage(
                    sender,
                    "script.check.all_completed",
                    args = listOf(total.toString(), passed.toString(), (total - passed).toString())
                )
            }
        }
    }

    fun checkScript(script: String, sender: CommandSender) {
        startOperation(sender) { session ->
            val project = snapshotWithLegacyWarning()
            if (!lifecycle.accepts(session)) return@startOperation
            if (project == null || project.files.none { it.name == script }) {
                Root.global {
                    LangManager.sendMessage(sender, "script.error.not_found", args = listOf(script.wrap()))
                }
                return@startOperation
            }

            Root.global {
                LangManager.sendMessage(sender, "script.check.one_started", args = listOf(script.wrap()))
            }
            if (!lifecycle.accepts(session)) return@startOperation
            ScriptManager.check(project, sender, announceName = script)
        }
    }

    fun unloadAll(sender: CommandSender) {
        startOperation(sender) { session ->
            if (!lifecycle.accepts(session)) return@startOperation
            val count = ScriptManager.clearNow()
            if (count == null) {
                Root.global {
                    LangManager.sendMessage(sender, "script.operation.busy")
                }
                return@startOperation
            }
            Root.global {
                LangManager.sendMessage(sender, "script.unload.all_completed", args = listOf(count.toString()))
            }
        }
    }

    fun reloadConfig(sender: CommandSender) {
        startOperation(sender) { session ->
            Root.global {
                if (lifecycle.accepts(session)) {
                    ReloadManager.reload(sender, false)
                }
            }
        }
    }

    fun clearCache(sender: CommandSender) {
        startOperation(sender) { session ->
            val cleared = synchronized(operationLock) {
                if (!lifecycle.accepts(session)) {
                    false
                } else {
                    ScriptCompilationCache.reset()
                    true
                }
            }
            if (!cleared) return@startOperation
            Root.global {
                LangManager.sendMessage(sender, "cache.cleared")
            }
        }
    }

    private fun startOperation(
        sender: CommandSender?,
        block: suspend (session: Long) -> Unit
    ): Boolean {
        val current = synchronized(operationLock) {
            val session = lifecycle.openSession() ?: return false
            if (operation != null) return@synchronized null

            val owner = Root.newGlobalTaskOwner()
            val created = Root.launch(context = owner, start = CoroutineStart.LAZY) {
                try {
                    if (lifecycle.accepts(session)) {
                        block(session)
                    }
                } catch (exception: Throwable) {
                    if (lifecycle.accepts(session)) {
                        Root.global {
                            LangManager.sendMessage(
                                sender,
                                "script.compile.error",
                                args = listOf(
                                    PROJECT_SCRIPT_NAME.wrap(),
                                    "-",
                                    "-",
                                    exception.message ?: exception.javaClass.simpleName
                                )
                            )
                            if (ConfigManager.value(Config.DEBUG)) {
                                Root.INSTANCE.logger.log(Level.WARNING, "EternalScript project operation failed.", exception)
                            }
                        }
                    }
                }
            }
            val createdOperation = ScriptProjectOperation(created, owner, session)
            operation = createdOperation
            created.invokeOnCompletion {
                synchronized(operationLock) {
                    if (operation === createdOperation) {
                        operation = null
                    }
                }
                Root.closeGlobalTaskOwner(owner)
            }
            createdOperation
        }

        if (current == null) {
            LangManager.sendMessage(sender, "script.operation.busy")
            return false
        }

        current.job.start()
        return true
    }

    private fun snapshotWithLegacyWarning() =
        scriptRepository.snapshot().also {
            warnLegacyScripts()
        }

    private fun warnLegacyScripts() {
        val legacy = scriptRepository.legacyPaths()
        legacyScriptWarning(legacy)?.let(Root.INSTANCE.logger::warning)
    }

    fun scriptPaths() = scriptRepository.paths()

    fun isActive() = synchronized(operationLock) {
        operation != null
    }
}

private data class ScriptProjectOperation(
    val job: Job,
    val owner: GlobalTaskOwner,
    val session: Long
)

internal class DataManagerLifecycle {
    private data class State(
        val session: Long,
        val open: Boolean
    )

    private val state = AtomicReference(State(session = 0, open = false))

    fun open(): Long =
        state.updateAndGet { current ->
            if (current.open) current else State(current.session + 1, true)
        }.session

    fun close(): Long =
        state.updateAndGet { current ->
            if (!current.open) current else State(current.session + 1, false)
        }.session

    fun openSession(): Long? =
        state.get().let { current ->
            current.session.takeIf { current.open }
        }

    fun accepts(session: Long): Boolean =
        state.get().let { current ->
            current.open && current.session == session
        }
}

internal fun awaitOperationShutdown(
    operation: Job?,
    timeoutMillis: Long,
    isGlobalThread: Boolean,
    pumpGlobalTasks: () -> Unit
): Boolean {
    require(timeoutMillis >= 0L) {
        "Operation shutdown timeout must not be negative."
    }
    if (operation == null || operation.isCompleted) return true
    if (!isGlobalThread) {
        return runBlocking {
            withTimeoutOrNull(timeoutMillis) {
                operation.join()
                true
            } ?: false
        }
    }

    val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val started = System.nanoTime()
    while (!operation.isCompleted) {
        pumpGlobalTasks()
        if (operation.isCompleted) return true

        val elapsed = System.nanoTime() - started
        if (elapsed >= timeoutNanos) return false
        LockSupport.parkNanos(
            minOf(SHUTDOWN_OPERATION_POLL_NANOS, timeoutNanos - elapsed)
        )
    }
    return true
}

private const val OPERATION_SHUTDOWN_TIMEOUT_MILLIS = 10_000L
private const val SHUTDOWN_OPERATION_POLL_NANOS = 100_000L
