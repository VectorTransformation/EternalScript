package eternalScript.core.manager

import eternalScript.core.environment.EnvironmentRefreshRequest

/**
 * Coalesces environment changes without ever invoking operation callbacks
 * while holding its queue lock.
 */
internal class EnvironmentRefreshController(
    private val lifecycle: ProjectLifecycleFence,
    private val canDrain: () -> Boolean,
    private val operationActive: () -> Boolean,
    private val dispatch: (EnvironmentRefreshRequest) -> Boolean
) {
    private val lock = Any()
    private var accepting = false
    private var pending: EnvironmentRefreshRequest? = null

    fun open() {
        synchronized(lock) {
            accepting = true
            pending = null
        }
    }

    fun close() {
        synchronized(lock) {
            accepting = false
            pending = null
        }
    }

    fun request(request: EnvironmentRefreshRequest) {
        if (lifecycle.openSession() == null) return
        val accepted = synchronized(lock) {
            if (!accepting) {
                false
            } else {
                pending = pending?.merge(request) ?: request
                true
            }
        }
        if (accepted && canDrain()) {
            drain()
        }
    }

    fun drain() {
        var immediateRetryUsed = false
        while (true) {
            if (lifecycle.openSession() == null || !canDrain() || operationActive()) {
                return
            }
            val request = synchronized(lock) {
                if (!accepting) {
                    null
                } else {
                    pending.also { pending = null }
                }
            } ?: return

            if (dispatch(request)) return

            synchronized(lock) {
                if (accepting) {
                    pending = request.merge(pending)
                }
            }
            if (immediateRetryUsed || operationActive()) return
            immediateRetryUsed = true
        }
    }

    internal fun pendingRequest(): EnvironmentRefreshRequest? =
        synchronized(lock) { pending }
}
