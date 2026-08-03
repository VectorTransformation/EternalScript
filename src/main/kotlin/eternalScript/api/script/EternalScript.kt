package eternalScript.api.script

/**
 * Entry point for one independently managed feature in a script project.
 *
 * EternalScript discovers every concrete subclass with an accessible
 * no-argument constructor, activates them in class-name order, and disables
 * them in reverse order when the project is replaced or unloaded.
 */
abstract class EternalScript protected constructor() : Script() {
    init {
        onEnable { onEnable() }
        onDisable { onDisable() }
    }

    protected open fun onEnable() {}

    protected open fun onDisable() {}
}
