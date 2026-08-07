package eternalScript.core.runtime

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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Coroutine ownership and Folia global-thread handoff for one plugin lifecycle. */
internal class GlobalExecution(
    private val server: ServerAccess
) {
    private val scopeLock = Any()
    @Volatile
    private var scope = newScope()
    private var lifecycleEpoch = 0L
    private var lifecycleOpen = false
    private val pendingGlobalTasks = GlobalTaskQueue()

    val semaphore = Semaphore(20)

    fun start() {
        synchronized(scopeLock) {
            if (lifecycleOpen) return
            pendingGlobalTasks.rejectAll(::staleGlobalTask)
            if (!scope.isActive) scope = newScope()
            lifecycleEpoch += 1
            lifecycleOpen = true
        }
    }

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

    fun newTaskOwner(): GlobalTaskOwner = synchronized(scopeLock) {
        check(lifecycleOpen) {
            "A global-task owner cannot be created outside an active plugin lifecycle."
        }
        GlobalTaskOwner(lifecycleEpoch)
    }

    fun beginTaskOwnerShutdown(owner: GlobalTaskOwner): Boolean = synchronized(scopeLock) {
        owner.beginShutdownDrain(lifecycleEpoch)
    }

    /** Closes admission while allowing one synchronously drained operation owner. */
    fun closeAdmission(drainingOwner: GlobalTaskOwner?) {
        synchronized(scopeLock) {
            lifecycleOpen = false
            scope.cancel()
        }
        pendingGlobalTasks.rejectAllExcept(drainingOwner, ::closedGlobalTask)
    }

    fun closeTaskOwner(owner: GlobalTaskOwner) {
        owner.close()
        pendingGlobalTasks.rejectOwner(owner, ::closedGlobalTask)
    }

    fun shutdown() {
        synchronized(scopeLock) {
            lifecycleOpen = false
            scope.cancel()
        }
        pendingGlobalTasks.rejectAll(::closedGlobalTask)
    }

    suspend fun <T> io(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO, block)

    suspend fun <T> global(block: () -> T): T {
        val owner = coroutineContext[GlobalTaskOwner]
        val snapshot = synchronized(scopeLock) {
            GlobalLifecycleSnapshot(lifecycleEpoch, lifecycleOpen)
        }
        ensureGlobalTaskAllowed(owner, snapshot)
        if (server.isGlobalTickThread) return block()

        return suspendCancellableCoroutine { continuation ->
            val task = synchronized(scopeLock) {
                val current = GlobalLifecycleSnapshot(lifecycleEpoch, lifecycleOpen)
                if (!globalTaskAllowed(owner, current)) {
                    null
                } else {
                    owner?.enqueueIfAllowed(current) {
                        enqueueGlobalTask(current, owner, continuation, block)
                    } ?: if (owner == null) {
                        enqueueGlobalTask(current, null, continuation, block)
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
                server.executeGlobal {
                    runGlobalTask(task)
                }
            } catch (exception: Throwable) {
                pendingGlobalTasks.reject(task, exception)
            }
        }
    }

    fun drainPendingGlobalTasks(owner: GlobalTaskOwner): Int {
        check(server.isGlobalTickThread) {
            "Pending global tasks may only be drained from the global tick thread."
        }
        return pendingGlobalTasks.drain(owner, ::runClaimedGlobalTask)
    }

    private fun <T> enqueueGlobalTask(
        snapshot: GlobalLifecycleSnapshot,
        owner: GlobalTaskOwner?,
        continuation: kotlin.coroutines.Continuation<T>,
        block: () -> T
    ): GlobalTaskQueue.Task = pendingGlobalTasks.enqueue(
        epoch = snapshot.epoch,
        owner = owner,
        action = {
            if (continuation.context[kotlinx.coroutines.Job]?.isActive != false) {
                continuation.resumeWith(runCatching(block))
            }
        },
        rejection = { exception ->
            if (continuation.context[kotlinx.coroutines.Job]?.isActive != false) {
                continuation.resumeWith(Result.failure(exception))
            }
        }
    )

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
        if (!globalTaskAllowed(owner, snapshot)) throw closedGlobalTask()
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

internal data class GlobalLifecycleSnapshot(val epoch: Long, val open: Boolean)

internal class GlobalTaskOwner internal constructor(
    val lifecycleEpoch: Long
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GlobalTaskOwner>

    private enum class State { ACTIVE, SHUTDOWN_DRAIN, CLOSED }

    private val monitor = Any()
    private var state = State.ACTIVE

    internal fun beginShutdownDrain(currentEpoch: Long): Boolean = synchronized(monitor) {
        if (lifecycleEpoch != currentEpoch || state == State.CLOSED) false else {
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

    internal fun allows(snapshot: GlobalLifecycleSnapshot): Boolean = synchronized(monitor) {
        allowsLocked(snapshot)
    }

    internal fun close() = synchronized(monitor) {
        state = State.CLOSED
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

    fun claim(task: Task): Task? = task.takeIf(tasks::remove)

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

    fun rejectAllExcept(owner: GlobalTaskOwner?, exception: () -> Throwable): Int =
        rejectMatching({ task -> task.owner !== owner }, exception)

    fun rejectAll(exception: () -> Throwable): Int = rejectMatching({ true }, exception)

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

private fun staleGlobalTask() = CancellationException(
    "The global handoff belongs to an expired EternalScript lifecycle."
)
