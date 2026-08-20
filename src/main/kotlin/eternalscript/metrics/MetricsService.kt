package eternalscript.metrics

import org.bstats.bukkit.Metrics
import org.bukkit.plugin.java.JavaPlugin

internal class MetricsService(
    private val plugin: JavaPlugin,
    private val enabled: () -> Boolean
) : AutoCloseable {
    private var metrics: Metrics? = null

    fun start() {
        refresh()
    }

    fun refresh() {
        if (enabled()) {
            if (metrics == null) metrics = Metrics(plugin, PLUGIN_ID)
        } else {
            close()
        }
    }

    override fun close() {
        metrics?.shutdown()
        metrics = null
    }

    private companion object {
        const val PLUGIN_ID: Int = 27192
    }
}
