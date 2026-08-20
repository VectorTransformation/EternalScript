package eternalscript.scripting.runtime

internal class ScriptLifecycleHooks(
    private val loadCallbacks: List<() -> Unit>,
    private val unloadCallbacks: List<() -> Unit>
) {
    fun invokeLoad() {
        loadCallbacks.forEach { callback -> callback() }
    }

    fun invokeUnload() {
        val failures = mutableListOf<Throwable>()
        unloadCallbacks.forEach { callback ->
            try {
                callback()
            } catch (error: Throwable) {
                failures += error
            }
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }
}
