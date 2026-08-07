package eternalScript.core.manager

import eternalScript.core.environment.EnvironmentRefreshRequest
import eternalScript.core.environment.ScriptEnvironmentCoordinator
import eternalScript.core.runtime.PluginHost
import eternalScript.core.runtime.ServerAccess
import eternalScript.core.script.classloading.ScriptGenerationRegistry
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServerLoadEvent

internal class ServerLifecycleListener(
    private val host: PluginHost,
    private val server: ServerAccess,
    private val lifecycle: ProjectLifecycleFence,
    private val scriptManager: ScriptManager,
    private val environment: ScriptEnvironmentCoordinator,
    private val generationRegistry: ScriptGenerationRegistry,
    private val requestRefresh: (EnvironmentRefreshRequest) -> Unit
) : Listener {
    private val lock = Any()
    private val initialLoad = InitialScriptLoadCoordinator()

    val serverLoaded: Boolean
        get() = synchronized(lock) { initialLoad.serverLoaded }

    fun start() {
        synchronized(lock) { initialLoad.reset() }
        server.registerEvent(ServerLoadEvent::class, this, EventPriority.MONITOR) { event ->
            val shouldLoad = synchronized(lock) {
                initialLoad.onServerLoad(
                    reload = event.type == ServerLoadEvent.LoadType.RELOAD
                )
            }
            requestRefresh(
                EnvironmentRefreshRequest(
                    capture = environment.capturePluginClasspath(),
                    loadScripts = shouldLoad
                )
            )
        }
        server.registerEvent(PluginEnableEvent::class, this, EventPriority.MONITOR) { event ->
            if (event.plugin !== host.plugin) {
                requestRefresh(
                    EnvironmentRefreshRequest(environment.capturePluginClasspath())
                )
            }
        }
        server.registerEvent(PluginDisableEvent::class, this, EventPriority.LOWEST) { event ->
            if (event.plugin !== host.plugin) {
                val pluginName = event.plugin.name
                scriptManager.invalidateEnvironment()
                generationRegistry.invalidate(pluginName)
                val frozen = scriptManager.freezeForDisabledPlugin(pluginName)
                requestRefresh(
                    EnvironmentRefreshRequest(
                        capture = environment.capturePluginClasspath(
                            excludedPlugin = event.plugin
                        ),
                        disabledPlugins = if (frozen) setOf(pluginName) else emptySet()
                    )
                )
            }
        }
        server.runGlobalDelayed(1L) {
            val sessionOpen = lifecycle.openSession() != null
            val shouldLoad = synchronized(lock) {
                initialLoad.onFallback(sessionOpen = sessionOpen)
            }
            if (shouldLoad) {
                requestRefresh(
                    EnvironmentRefreshRequest(
                        capture = environment.capturePluginClasspath(),
                        loadScripts = true
                    )
                )
            }
        }
    }

    fun stop() {
        server.unregister(this)
        synchronized(lock) { initialLoad.reset() }
    }
}
