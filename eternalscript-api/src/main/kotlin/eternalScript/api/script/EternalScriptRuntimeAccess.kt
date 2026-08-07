package eternalScript.api.script

/** Controlled lifecycle access used by the core runtime. */
@InternalEternalScriptRuntimeApi
@OptIn(InternalEternalScriptRuntimeApi::class)
object EternalScriptRuntimeAccess {
    fun attach(
        script: EternalScript,
        bridge: EternalScriptRuntimeBridge
    ) {
        script.attachRuntime(bridge)
    }

    fun enable(script: EternalScript) {
        script.invokeEnable()
    }

    fun disable(script: EternalScript) {
        script.invokeDisable()
    }

    fun detach(
        script: EternalScript,
        bridge: EternalScriptRuntimeBridge
    ) {
        script.detachRuntime(bridge)
    }
}
