package eternalScript.core.script.manager

import kotlinx.coroutines.Job
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap

class ScriptTaskManager {
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    private val tasks = ConcurrentHashMap.newKeySet<BukkitTask>()

    fun <T : Job> track(job: T): T {
        jobs.add(job)
        job.invokeOnCompletion {
            jobs.remove(job)
        }
        return job
    }

    fun <T : BukkitTask> track(task: T): T {
        tasks.add(task)
        return task
    }

    fun clear() {
        jobs.toList().forEach { job ->
            runCatching(job::cancel)
        }
        tasks.toList().forEach { task ->
            runCatching(task::cancel)
        }
        jobs.clear()
        tasks.clear()
    }
}
