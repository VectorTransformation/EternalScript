package eternalScript.core.script.data

/**
 * Limits dynamic command and listener registration to the thread currently
 * executing a script's enable lifecycle.
 *
 * The execution gate cannot represent this by itself: a generation may be in
 * STAGED or SWAPPING state while unrelated callbacks are still finishing on
 * another thread.
 */
class ScriptRegistrationGate {
    private val depth = ThreadLocal.withInitial { 0 }

    internal val isOpen: Boolean
        get() = depth.get() > 0

    internal fun <T> withOpen(block: () -> T): T {
        depth.set(depth.get() + 1)
        return try {
            block()
        } finally {
            val remaining = depth.get() - 1
            if (remaining == 0) {
                depth.remove()
            } else {
                depth.set(remaining)
            }
        }
    }
}
