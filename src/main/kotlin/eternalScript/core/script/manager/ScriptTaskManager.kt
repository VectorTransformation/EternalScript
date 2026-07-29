package eternalScript.core.script.manager

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask

internal class ScriptTaskManager(
    private val taskIsRunning: (BukkitTask) -> Boolean = { task ->
        Bukkit.getScheduler().isCurrentlyRunning(task.taskId)
    },
    private val taskIsLive: (BukkitTask) -> Boolean = { task ->
        Bukkit.getScheduler().run {
            isQueued(task.taskId) || isCurrentlyRunning(task.taskId)
        }
    },
    private val scheduledTaskIsRunning: (ScheduledTask) -> Boolean = { task ->
        task.executionState == ScheduledTask.ExecutionState.RUNNING ||
            task.executionState == ScheduledTask.ExecutionState.CANCELLED_RUNNING
    },
    private val scheduledTaskIsLive: (ScheduledTask) -> Boolean = { task ->
        task.executionState != ScheduledTask.ExecutionState.FINISHED &&
            task.executionState != ScheduledTask.ExecutionState.CANCELLED
    }
) {
    private val lifecycleLock = Any()
    private val jobs = mutableSetOf<Job>()
    private val tasks = mutableSetOf<BukkitTask>()
    private val scheduledTasks = mutableSetOf<ScheduledTask>()
    private var accepting = false

    internal fun open() {
        synchronized(lifecycleLock) {
            accepting = true
        }
    }

    internal fun close() {
        synchronized(lifecycleLock) {
            accepting = false
        }
    }

    internal fun <T : Job> track(job: T): T {
        val accepted = synchronized(lifecycleLock) {
            jobs.add(job)
            accepting
        }
        job.invokeOnCompletion {
            synchronized(lifecycleLock) {
                jobs.remove(job)
            }
        }
        if (!accepted) {
            runCatching(job::cancel)
        }
        return job
    }

    internal fun <T : BukkitTask> track(task: T): T {
        val accepted = synchronized(lifecycleLock) {
            if (accepting) {
                tasks.removeAll { tracked ->
                    !isLive(tracked)
                }
            }
            tasks.add(task)
            accepting
        }
        if (!accepted) {
            runCatching(task::cancel)
        }
        return task
    }

    internal fun <T : ScheduledTask> track(task: T): T {
        val accepted = synchronized(lifecycleLock) {
            if (accepting) {
                scheduledTasks.removeAll { tracked ->
                    !isLive(tracked)
                }
            }
            scheduledTasks.add(task)
            accepting
        }
        if (!accepted) {
            runCatching(task::cancel)
        }
        return task
    }

    internal suspend fun cancelTrackedWorkAndJoin(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) {
            "Timeout must not be negative."
        }
        synchronized(lifecycleLock) {
            accepting = false
        }

        if (timeoutMillis == 0L) {
            cancelAndPrune(snapshot())
            return isEmpty()
        }

        return withTimeoutOrNull(timeoutMillis) {
            var drained = false
            while (!drained) {
                val snapshot = snapshot()
                cancelAndPrune(snapshot)
                snapshot.jobs.joinAll()
                drained = isEmpty()
                if (!drained) {
                    delay(TASK_DRAIN_POLL_MILLIS)
                }
            }
            true
        } ?: false
    }

    internal fun clear() {
        val snapshot = synchronized(lifecycleLock) {
            accepting = false
            val snapshot = snapshotLocked()
            jobs.clear()
            tasks.clear()
            scheduledTasks.clear()
            snapshot
        }
        snapshot.jobs.forEach { job ->
            runCatching(job::cancel)
        }
        snapshot.tasks.forEach { task ->
            runCatching(task::cancel)
        }
        snapshot.scheduledTasks.forEach { task ->
            runCatching(task::cancel)
        }
    }

    private fun snapshot() = synchronized(lifecycleLock) {
        snapshotLocked()
    }

    private fun snapshotLocked() = TrackedWork(
        jobs = jobs.toList(),
        tasks = tasks.toList(),
        scheduledTasks = scheduledTasks.toList()
    )

    private fun cancelAndPrune(snapshot: TrackedWork) {
        snapshot.jobs.forEach { job ->
            runCatching(job::cancel)
        }
        val cancelledTasks = snapshot.tasks.filter { task ->
            runCatching(task::cancel).isSuccess
        }
        val cancelledScheduledTasks = snapshot.scheduledTasks.filter { task ->
            runCatching(task::cancel).isSuccess
        }
        prune(
            snapshot.copy(
                tasks = cancelledTasks,
                scheduledTasks = cancelledScheduledTasks
            )
        )
    }

    private fun prune(snapshot: TrackedWork) {
        val finishedJobs = snapshot.jobs.filter(Job::isCompleted)
        val stoppedTasks = snapshot.tasks.filterNot(::isStillRunning)
        val stoppedScheduledTasks = snapshot.scheduledTasks.filterNot(::isStillRunning)
        synchronized(lifecycleLock) {
            jobs.removeAll(finishedJobs)
            tasks.removeAll(stoppedTasks)
            scheduledTasks.removeAll(stoppedScheduledTasks)
        }
    }

    private fun isEmpty() = synchronized(lifecycleLock) {
        jobs.isEmpty() && tasks.isEmpty() && scheduledTasks.isEmpty()
    }

    private fun isStillRunning(task: BukkitTask) =
        runCatching {
            taskIsRunning(task)
        }.getOrDefault(true)

    private fun isStillRunning(task: ScheduledTask) =
        runCatching {
            scheduledTaskIsRunning(task)
        }.getOrDefault(true)

    private fun isLive(task: BukkitTask) =
        runCatching {
            taskIsLive(task)
        }.getOrDefault(true)

    private fun isLive(task: ScheduledTask) =
        runCatching {
            scheduledTaskIsLive(task)
        }.getOrDefault(true)
}

private data class TrackedWork(
    val jobs: List<Job>,
    val tasks: List<BukkitTask>,
    val scheduledTasks: List<ScheduledTask>
)

private const val TASK_DRAIN_POLL_MILLIS = 1L
