package eternalScript.core.script.runtime

/**
 * Separates definitions created while a Script instance is being constructed
 * from registrations owned by one enable/disable cycle.
 *
 * Constructor definitions survive a rollback. Activation definitions do not:
 * they are discarded on deactivation and rebuilt by the next onEnable call.
 */
internal class ScriptRegistrationLifecycle<T> {
    private val monitor = Any()
    private val constructorDefinitions = mutableListOf<T>()
    private val activationDefinitions = mutableListOf<T>()
    private val runtimeRegistrations = mutableListOf<T>()
    private var phase = Phase.CONSTRUCTING

    fun beginActivation() {
        synchronized(monitor) {
            check(phase == Phase.CONSTRUCTING || phase == Phase.INACTIVE) {
                "Script registration lifecycle cannot begin activation from $phase."
            }
            activationDefinitions.clear()
            runtimeRegistrations.clear()
            phase = Phase.ACTIVATING
        }
    }

    fun add(value: T, registrationGateOpen: Boolean): Placement =
        synchronized(monitor) {
            when (phase) {
                Phase.CONSTRUCTING -> {
                    constructorDefinitions += value
                    Placement.STAGED
                }

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
                    "Registrations can only be added during construction or the enable lifecycle."
                )

                Phase.DISPOSED -> error("The Script registration lifecycle is disposed.")
            }
        }

    /** Marks the cycle active before callers publish its definitions. */
    fun activate(): List<T> = synchronized(monitor) {
        check(phase == Phase.ACTIVATING) {
            "Script registration lifecycle cannot activate from $phase."
        }
        phase = Phase.ACTIVE
        currentDefinitionsLocked()
    }

    /**
     * Ends one cycle. Constructor definitions remain staged for a rollback;
     * every definition created by onEnable is released with this cycle.
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
        constructorDefinitions.clear()
        activationDefinitions.clear()
        runtimeRegistrations.clear()
        phase = Phase.DISPOSED
        Release(registered, wasActive)
    }

    fun snapshot(): List<T> = synchronized(monitor) {
        currentDefinitionsLocked()
    }

    private fun currentDefinitionsLocked(): List<T> =
        (constructorDefinitions + activationDefinitions + runtimeRegistrations).toList()

    enum class Placement {
        STAGED,
        LIVE
    }

    data class Release<T>(
        val registrations: List<T>,
        val wasActive: Boolean
    )

    private enum class Phase {
        CONSTRUCTING,
        ACTIVATING,
        ACTIVE,
        INACTIVE,
        DISPOSED
    }
}
