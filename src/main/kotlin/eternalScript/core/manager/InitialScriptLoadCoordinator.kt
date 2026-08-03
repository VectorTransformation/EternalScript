package eternalScript.core.manager

internal class InitialScriptLoadCoordinator {
    var serverLoaded: Boolean = false
        private set
    private var loadRequested: Boolean = false

    fun onServerLoad(reload: Boolean): Boolean {
        serverLoaded = true
        val first = !loadRequested
        loadRequested = true
        return first || reload
    }

    fun onFallback(sessionOpen: Boolean): Boolean {
        if (loadRequested || !sessionOpen) return false
        loadRequested = true
        serverLoaded = true
        return true
    }

    fun reset() {
        serverLoaded = false
        loadRequested = false
    }
}
