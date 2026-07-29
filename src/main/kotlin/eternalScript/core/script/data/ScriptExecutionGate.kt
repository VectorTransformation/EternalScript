package eternalScript.core.script.data

class ScriptExecutionGate {
    enum class State {
        STAGED,
        ACTIVE,
        SWAPPING,
        RETIRED
    }

    private val monitor = Any()
    private var currentState = State.STAGED
    private var readers = 0
    @Volatile
    private var exceptionMapper: (Throwable) -> Throwable = { it }

    val state: State
        get() = synchronized(monitor) {
            currentState
        }

    val isActive: Boolean
        get() = state == State.ACTIVE

    val isDrained: Boolean
        get() = synchronized(monitor) {
            readers == 0
        }

    internal val readerCount: Int
        get() = synchronized(monitor) {
            readers
        }

    fun <T> withActive(block: () -> T): T? {
        synchronized(monitor) {
            if (currentState != State.ACTIVE) return null
            readers += 1
        }

        return try {
            block()
        } catch (exception: Throwable) {
            throw runCatching {
                exceptionMapper(exception)
            }.getOrDefault(exception)
        } finally {
            synchronized(monitor) {
                check(readers > 0) {
                    "Script execution gate reader count underflow."
                }
                readers -= 1
            }
        }
    }

    internal fun tryFreeze(): Boolean = synchronized(monitor) {
        if (currentState != State.ACTIVE) {
            false
        } else {
            currentState = State.SWAPPING
            true
        }
    }

    internal fun publish(): Boolean = transition(State.STAGED, State.ACTIVE)

    internal fun restore(): Boolean = transition(State.SWAPPING, State.ACTIVE)

    internal fun retire(): Boolean = synchronized(monitor) {
        if (currentState == State.RETIRED) {
            false
        } else {
            currentState = State.RETIRED
            true
        }
    }

    internal fun close(): Boolean = retire()

    internal fun mapExceptions(mapper: (Throwable) -> Throwable) {
        exceptionMapper = mapper
    }

    private fun transition(expected: State, target: State): Boolean = synchronized(monitor) {
        if (currentState != expected) {
            false
        } else {
            currentState = target
            true
        }
    }
}
