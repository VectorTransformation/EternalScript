package eternalScript.core.script.runtime

/**
 * Owns definitions created by one enable/disable cycle.
 * Definitions are discarded on deactivation and rebuilt by the next onEnable.
 */
internal class ScriptRegistrationLifecycle<T> {
    private val monitor = Any()
    private val activationDefinitions = mutableListOf<T>()
    private val runtimeRegistrations = mutableListOf<T>()
    private var phase = Phase.INACTIVE

    fun beginActivation() {
        synchronized(monitor) {
            check(phase == Phase.INACTIVE) {
                "EternalScript registration lifecycle cannot begin activation from $phase."
            }
            activationDefinitions.clear()
            runtimeRegistrations.clear()
            phase = Phase.ACTIVATING
        }
    }

    fun add(value: T, registrationGateOpen: Boolean): Placement =
        synchronized(monitor) {
            when (phase) {
                Phase.ACTIVATING -> {
                    check(registrationGateOpen) {
                        "Registrations created during activation must run on the enable lifecycle thread."
                    }
                    activationDefinitions += value
                    Placement.STAGED
                }

                Phase.ACTIVE -> {
                    check(registrationGateOpen) {
                        "Registrations can only be added during the enable lifecycle."
                    }
                    runtimeRegistrations += value
                    Placement.LIVE
                }

                Phase.INACTIVE -> error(
                    "Commands and events can only be registered from onEnable()."
                )

                Phase.DISPOSED -> error(
                    "The EternalScript registration lifecycle is disposed."
                )
            }
        }

    /** Marks the cycle active before callers publish its definitions. */
    fun activate(): List<T> = synchronized(monitor) {
        check(phase == Phase.ACTIVATING) {
            "EternalScript registration lifecycle cannot activate from $phase."
        }
        phase = Phase.ACTIVE
        currentDefinitionsLocked()
    }

    /**
     * Ends one cycle and releases every definition created by onEnable.
     */
    fun deactivate(): Release<T> = synchronized(monitor) {
        if (phase == Phase.INACTIVE || phase == Phase.DISPOSED) {
            return@synchronized Release(emptyList(), wasActive = false)
        }
        val wasActive = phase == Phase.ACTIVE
        val registered = if (wasActive) currentDefinitionsLocked() else emptyList()
        activationDefinitions.clear()
        runtimeRegistrations.clear()
        phase = Phase.INACTIVE
        Release(registered, wasActive)
    }

    fun dispose(): Release<T> = synchronized(monitor) {
        if (phase == Phase.DISPOSED) {
            return@synchronized Release(emptyList(), wasActive = false)
        }
        val wasActive = phase == Phase.ACTIVE
        val registered = if (wasActive) currentDefinitionsLocked() else emptyList()
        activationDefinitions.clear()
        runtimeRegistrations.clear()
        phase = Phase.DISPOSED
        Release(registered, wasActive)
    }

    fun snapshot(): List<T> = synchronized(monitor) {
        currentDefinitionsLocked()
    }

    private fun currentDefinitionsLocked(): List<T> =
        (activationDefinitions + runtimeRegistrations).toList()

    enum class Placement {
        STAGED,
        LIVE
    }

    data class Release<T>(
        val registrations: List<T>,
        val wasActive: Boolean
    )

    private enum class Phase {
        ACTIVATING,
        ACTIVE,
        INACTIVE,
        DISPOSED
    }
}
