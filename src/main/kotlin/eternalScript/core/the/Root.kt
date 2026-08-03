package eternalScript.core.the

import eternalScript.EternalScript
import eternalScript.api.command.CommandBuilder
import eternalScript.api.manager.PluginStartable
import eternalScript.api.manager.PluginStoppable
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KClass

object Root {
    const val ORIGIN = "EternalScript"

    val INSTANCE = pluginManager().getPlugin(ORIGIN) as EternalScript

    fun pluginManager() = Bukkit.getPluginManager()

    fun plugins() = pluginManager().plugins

    // event

    fun <T : Event> register(
        event: KClass<T>,
        listener: Listener,
        priority: EventPriority = EventPriority.NORMAL,
        block: (T) -> Unit
    ) = pluginManager().registerEvent(
        event.java,
        listener,
        priority,
        { _, executor ->
            if (event.java.isInstance(executor)) {
                block(event.java.cast(executor))
            }
        },
        INSTANCE
    )

    fun unregister(vararg listener: Listener) = listener.forEach(HandlerList::unregisterAll)

    // command

    fun lifecycleManager() = INSTANCE.lifecycleManager

    private fun registerEventHandler(commandBuilder: CommandBuilder) = lifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS) { handler ->
        handler.registrar().register(commandBuilder.builder.build(), commandBuilder.description, commandBuilder.aliases)
    }

    // util

    fun register(vararg commandBuilder: CommandBuilder) = commandBuilder.forEach(::registerEventHandler)

    fun start(vararg component: PluginStartable) = component.forEach(PluginStartable::start)

    fun stop(vararg component: PluginStoppable) = component.forEach(PluginStoppable::stop)

    fun dataFolder() = INSTANCE.dataFolder

    fun onlinePlayers() = Bukkit.getOnlinePlayers()

    fun classLoader(plugin: String) = pluginManager().getPlugin(plugin)?.javaClass?.classLoader

    private val scopeLock = Any()
    @Volatile
    private var scope = newScope()
    private var lifecycleEpoch = 0L
    private var lifecycleOpen = false

    val semaphore = Semaphore(20)
    private val pendingGlobalTasks = GlobalTaskQueue()

    fun launch(
        context: CoroutineContext = Dispatchers.Default,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ) = scope.launch(context, start, block)

    fun <T> async(
        context: CoroutineContext = Dispatchers.Default,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T
    ) = scope.async(context, start, block)

    fun startup() {
        synchronized(scopeLock) {
            if (lifecycleOpen) return
            pendingGlobalTasks.rejectAll(::staleGlobalTask)
            if (!scope.isActive) {
                scope = newScope()
            }
            lifecycleEpoch += 1
            lifecycleOpen = true
        }
    }

    internal fun newGlobalTaskOwner(): GlobalTaskOwner =
        synchronized(scopeLock) {
            check(lifecycleOpen) {
                "A global-task owner cannot be created outside an active plugin lifecycle."
            }
            GlobalTaskOwner(lifecycleEpoch)
        }

    internal fun beginGlobalTaskOwnerShutdown(owner: GlobalTaskOwner): Boolean =
        synchronized(scopeLock) {
            owner.beginShutdownDrain(lifecycleEpoch)
        }

    fun shutdown() {
        shutdown(null)
    }

    internal fun shutdown(drainingOwner: GlobalTaskOwner?) {
        synchronized(scopeLock) {
            lifecycleOpen = false
            scope.cancel()
        }
        pendingGlobalTasks.rejectAllExcept(drainingOwner, ::closedGlobalTask)
    }

    internal fun closeGlobalTaskOwner(owner: GlobalTaskOwner) {
        owner.close()
        pendingGlobalTasks.rejectOwner(owner, ::closedGlobalTask)
    }

    suspend fun <T> ioContext(
        block: suspend CoroutineScope.() -> T
    ) = withContext(Dispatchers.IO, block)

    suspend fun <T> global(block: () -> T): T {
        val owner = coroutineContext[GlobalTaskOwner]
        val snapshot = synchronized(scopeLock) {
            GlobalLifecycleSnapshot(lifecycleEpoch, lifecycleOpen)
        }
        ensureGlobalTaskAllowed(owner, snapshot)
        if (Bukkit.isGlobalTickThread()) return block()

        return suspendCancellableCoroutine { continuation ->
            val task = synchronized(scopeLock) {
                val current = GlobalLifecycleSnapshot(lifecycleEpoch, lifecycleOpen)
                if (!globalTaskAllowed(owner, current)) {
                    null
                } else {
                    owner?.enqueueIfAllowed(current) {
                        pendingGlobalTasks.enqueue(
                            epoch = current.epoch,
                            owner = owner,
                            action = {
                                if (continuation.isActive) {
                                    continuation.resumeWith(runCatching(block))
                                }
                            },
                            rejection = { exception ->
                                if (continuation.isActive) {
                                    continuation.resumeWith(Result.failure(exception))
                                }
                            }
                        )
                    } ?: if (owner == null) {
                        pendingGlobalTasks.enqueue(
                            epoch = current.epoch,
                            owner = null,
                            action = {
                                if (continuation.isActive) {
                                    continuation.resumeWith(runCatching(block))
                                }
                            },
                            rejection = { exception ->
                                if (continuation.isActive) {
                                    continuation.resumeWith(Result.failure(exception))
                                }
                            }
                        )
                    } else {
                        null
                    }
                }
            }
            if (task == null) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(closedGlobalTask()))
                }
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                pendingGlobalTasks.cancel(task)
            }
            try {
                INSTANCE.server.globalRegionScheduler.execute(INSTANCE) {
                    runGlobalTask(task)
                }
            } catch (exception: Throwable) {
                pendingGlobalTasks.reject(task, exception)
            }
        }
    }

    /**
     * Completes only one script operation's queued [global] handoffs while
     * plugin shutdown is synchronously waiting on the global tick thread.
     * Unowned and differently owned callbacks are never executed by this
     * shutdown pump.
     */
    internal fun drainPendingGlobalTasks(owner: GlobalTaskOwner): Int {
        check(Bukkit.isGlobalTickThread()) {
            "Pending global tasks may only be drained from the global tick thread."
        }
        return pendingGlobalTasks.drain(owner, ::runClaimedGlobalTask)
    }

    private fun runGlobalTask(task: GlobalTaskQueue.Task): Boolean =
        pendingGlobalTasks.claim(task)?.let(::runClaimedGlobalTask) != null

    private fun runClaimedGlobalTask(task: GlobalTaskQueue.Task) {
        val snapshot = synchronized(scopeLock) {
            GlobalLifecycleSnapshot(lifecycleEpoch, lifecycleOpen)
        }
        if (globalTaskAllowed(task.owner, snapshot, task.epoch)) {
            task.run()
        } else {
            task.reject(staleGlobalTask())
        }
    }

    private fun ensureGlobalTaskAllowed(
        owner: GlobalTaskOwner?,
        snapshot: GlobalLifecycleSnapshot
    ) {
        if (!globalTaskAllowed(owner, snapshot)) {
            throw closedGlobalTask()
        }
    }

    private fun globalTaskAllowed(
        owner: GlobalTaskOwner?,
        snapshot: GlobalLifecycleSnapshot,
        taskEpoch: Long = snapshot.epoch
    ): Boolean {
        if (taskEpoch != snapshot.epoch) return false
        return owner?.allows(snapshot) ?: snapshot.open
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

internal data class GlobalLifecycleSnapshot(
    val epoch: Long,
    val open: Boolean
)

internal class GlobalTaskOwner internal constructor(
    val lifecycleEpoch: Long
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GlobalTaskOwner>

    private enum class State {
        ACTIVE,
        SHUTDOWN_DRAIN,
        CLOSED
    }

    private val monitor = Any()
    private var state = State.ACTIVE

    internal fun beginShutdownDrain(currentEpoch: Long): Boolean =
        synchronized(monitor) {
            if (lifecycleEpoch != currentEpoch || state == State.CLOSED) {
                false
            } else {
                state = State.SHUTDOWN_DRAIN
                true
            }
        }

    internal fun <T> enqueueIfAllowed(
        snapshot: GlobalLifecycleSnapshot,
        enqueue: () -> T
    ): T? = synchronized(monitor) {
        if (!allowsLocked(snapshot)) null else enqueue()
    }

    internal fun allows(snapshot: GlobalLifecycleSnapshot): Boolean =
        synchronized(monitor) {
            allowsLocked(snapshot)
        }

    internal fun close() {
        synchronized(monitor) {
            state = State.CLOSED
        }
    }

    private fun allowsLocked(snapshot: GlobalLifecycleSnapshot): Boolean {
        if (lifecycleEpoch != snapshot.epoch) return false
        return when (state) {
            State.ACTIVE -> snapshot.open
            State.SHUTDOWN_DRAIN -> true
            State.CLOSED -> false
        }
    }
}

internal class GlobalTaskQueue {
    internal class Task(
        val epoch: Long,
        val owner: GlobalTaskOwner?,
        private val action: () -> Unit,
        private val rejection: (Throwable) -> Unit
    ) {
        fun run() = action()

        fun reject(exception: Throwable) = rejection(exception)
    }

    private val tasks = ConcurrentLinkedQueue<Task>()

    fun enqueue(
        epoch: Long,
        owner: GlobalTaskOwner?,
        action: () -> Unit,
        rejection: (Throwable) -> Unit = {}
    ): Task = Task(epoch, owner, action, rejection).also(tasks::add)

    fun claim(task: Task): Task? =
        task.takeIf(tasks::remove)

    fun cancel(task: Task): Boolean = tasks.remove(task)

    fun reject(task: Task, exception: Throwable): Boolean {
        val claimed = claim(task) ?: return false
        claimed.reject(exception)
        return true
    }

    fun drain(owner: GlobalTaskOwner, run: (Task) -> Unit): Int {
        var completed = 0
        tasks.toList().forEach { task ->
            if (task.owner === owner) {
                claim(task)?.let { claimed ->
                    run(claimed)
                    completed += 1
                }
            }
        }
        return completed
    }

    fun rejectOwner(owner: GlobalTaskOwner, exception: () -> Throwable): Int =
        rejectMatching({ task -> task.owner === owner }, exception)

    fun rejectAllExcept(
        owner: GlobalTaskOwner?,
        exception: () -> Throwable
    ): Int = rejectMatching({ task -> task.owner !== owner }, exception)

    fun rejectAll(exception: () -> Throwable): Int =
        rejectMatching({ true }, exception)

    private fun rejectMatching(
        predicate: (Task) -> Boolean,
        exception: () -> Throwable
    ): Int {
        var rejected = 0
        tasks.toList().forEach { task ->
            if (predicate(task)) {
                claim(task)?.let { claimed ->
                    claimed.reject(exception())
                    rejected += 1
                }
            }
        }
        return rejected
    }
}

private fun closedGlobalTask() =
    CancellationException("The EternalScript lifecycle is shutting down.")

private fun staleGlobalTask() =
    CancellationException("The global handoff belongs to an expired EternalScript lifecycle.")
