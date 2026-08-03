package eternalScript.core.script.generation

internal class ScriptInstanceLifecycleState {
    private val monitor = Any()
    private var active = false

    fun activate(block: () -> Unit) {
        synchronized(monitor) {
            if (active) return
            active = true
            block()
        }
    }

    fun deactivate(block: () -> Unit) {
        synchronized(monitor) {
            if (!active) return
            active = false
            block()
        }
    }
}
