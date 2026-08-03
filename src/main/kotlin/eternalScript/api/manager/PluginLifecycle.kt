package eternalScript.api.manager

/**
 * A plugin component that has an explicit startup phase.
 *
 * Components that only need startup do not have to expose a fake shutdown
 * implementation just to satisfy a broad manager contract.
 */
interface PluginStartable {
    fun start()
}

/**
 * A plugin component that owns resources and must be stopped explicitly.
 */
interface PluginStoppable {
    fun stop()
}
