package eternalscript.storage

import eternalscript.api.script.storage.ScriptTask
import eternalscript.api.script.storage.Storage
import eternalscript.api.script.storage.StorageKey
import eternalscript.api.script.storage.StorageNames
import eternalscript.api.script.storage.StorageScope
import eternalscript.api.script.storage.StorageTransaction
import eternalscript.logging.UnifiedLoggingService
import eternalscript.scripting.runtime.ScriptExecutionContext
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

internal object ScriptStorageRuntime {
    private data class Runtime(
        val service: SQLiteStorageService,
        val mainDispatcher: CoroutineDispatcher,
        val logging: UnifiedLoggingService
    )

    private val current = AtomicReference<Runtime?>()

    fun install(
        plugin: JavaPlugin,
        service: SQLiteStorageService,
        logging: UnifiedLoggingService
    ): AutoCloseable {
        val runtime = Runtime(
            service = service,
            mainDispatcher = PaperMainDispatcher(plugin),
            logging = logging
        )
        check(current.compareAndSet(null, runtime)) { "A script storage runtime is already installed" }
        return AutoCloseable { current.compareAndSet(runtime, null) }
    }

    fun storage(namespace: String): Storage = RuntimeStorage(StorageNames.requireNamespace(namespace))

    fun launch(owner: ScriptTaskOwner, block: suspend () -> Unit): ScriptTask {
        val runtime = requireRuntime()
        val lease = ScriptExecutionContext.acquireCurrent()
        return try {
            owner.launch(runtime.mainDispatcher, lease, runtime.logging::storageTaskFailure, block)
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    private fun requireRuntime(): Runtime = current.get()
        ?: error("Persistent storage is unavailable because EternalScript is not active")

    private fun service(): SQLiteStorageService = requireRuntime().service

    private class RuntimeStorage(
        override val namespace: String
    ) : Storage {
        override fun global(): StorageScope = RuntimeStorageScope(
            StorageAddress(namespace, "global", "global")
        )

        override fun player(uuid: java.util.UUID): StorageScope = RuntimeStorageScope(
            StorageAddress(namespace, "player", uuid.toString())
        )

        override fun world(uuid: java.util.UUID): StorageScope = RuntimeStorageScope(
            StorageAddress(namespace, "world", uuid.toString())
        )
    }

    private class RuntimeStorageScope(
        private val address: StorageAddress
    ) : StorageScope {
        override suspend fun <T> get(key: StorageKey<T>): T =
            tracked("get", key.name) { service().read(address, key) }

        override suspend fun <T> set(key: StorageKey<T>, value: T) {
            tracked("set", key.name) { service().set(address, key, value) }
        }

        override suspend fun contains(key: StorageKey<*>): Boolean =
            tracked("contains", key.name) { service().contains(address, key) }

        override suspend fun remove(key: StorageKey<*>) {
            tracked("remove", key.name) { service().remove(address, key) }
        }

        override suspend fun <R> update(block: StorageTransaction.() -> R): R =
            tracked("update", null) { service().transaction(address, block) }

        private suspend fun <T> tracked(
            operation: String,
            key: String?,
            block: suspend () -> T
        ): T {
            val task = requireStorageTask()
            val started = System.nanoTime()
            try {
                return block()
            } finally {
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                requireRuntime().logging.slowStorageOperation(
                    task.source,
                    address.namespace,
                    address.type,
                    operation,
                    key,
                    elapsedMillis
                )
            }
        }

        private suspend fun requireStorageTask(): StorageTaskContext {
            return checkNotNull(coroutineContext[StorageTaskContext]) {
                "Persistent storage operations may only run inside storageTask"
            }
        }
    }
}

internal class ScriptTaskOwner : AutoCloseable {
    private val lock = Any()
    private var source: String = "<script>"
    private var scope: CoroutineScope? = null
    private var disposed = false

    fun attachSource(path: String) {
        synchronized(lock) {
            check(source == "<script>" || source == path) {
                "Script storage task owner is already attached to $source"
            }
            source = path
        }
    }

    fun launch(
        mainDispatcher: CoroutineDispatcher,
        lease: ScriptExecutionContext.ScriptExecutionLease,
        reportFailure: (String, Throwable) -> Unit,
        block: suspend () -> Unit
    ): ScriptTask {
        val launched = synchronized(lock) {
            check(!disposed) { "Script storage tasks are already disposed" }
            val activeScope = scope ?: createScope(mainDispatcher, reportFailure).also { scope = it }
            activeScope.launch(context = lease.coroutineContext() + StorageTaskContext(source)) { block() }
        }
        launched.invokeOnCompletion { lease.close() }
        return JobScriptTask(launched)
    }

    override fun close() {
        val active = synchronized(lock) {
            if (disposed) return
            disposed = true
            scope.also {
                scope = null
            }
        }
        active?.cancel("Script was unloaded")
    }

    private fun createScope(
        mainDispatcher: CoroutineDispatcher,
        reportFailure: (String, Throwable) -> Unit
    ): CoroutineScope {
        val parent = SupervisorJob()
        val handler = CoroutineExceptionHandler { _, error ->
            if (error !is CancellationException) reportFailure(source, error)
        }
        return CoroutineScope(parent + mainDispatcher + handler)
    }
}

private class StorageTaskContext(
    val source: String
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key

    override fun toString(): String = "EternalScriptStorageTask"

    companion object Key : CoroutineContext.Key<StorageTaskContext>
}

private class JobScriptTask(private val job: Job) : ScriptTask {
    override val isActive: Boolean
        get() = job.isActive

    override fun cancel() {
        job.cancel(CancellationException("Storage task was cancelled by the script"))
    }
}

private class PaperMainDispatcher(
    private val plugin: JavaPlugin
) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        plugin.server.scheduler.runTask(plugin, block)
    }
}
