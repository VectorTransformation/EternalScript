package eternalScript.core.manager

import eternalScript.core.metrics.Metrics
import org.bukkit.plugin.Plugin

internal class MetricsService(
    private val plugin: Plugin,
    private val enabled: () -> Boolean,
    private val metricsFactory: (Plugin, Int) -> MetricsHandle = { current, id ->
        RealMetricsHandle(Metrics(current, id))
    }
) : PluginStartable, PluginStoppable {
    private val monitor = Any()
    private var metrics: MetricsHandle? = null

    override fun start() {
        synchronized(monitor) {
            if (metrics != null || !enabled()) return
            metrics = metricsFactory(plugin, PLUGIN_ID)
        }
    }

    override fun stop() {
        val current = synchronized(monitor) {
            metrics.also { metrics = null }
        }
        current?.shutdown()
    }

    private companion object {
        const val PLUGIN_ID = 27192
    }
}

internal fun interface MetricsHandle {
    fun shutdown()
}

private class RealMetricsHandle(
    private val metrics: Metrics
) : MetricsHandle {
    override fun shutdown() = metrics.shutdown()
}
