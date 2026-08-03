package eternalScript.core.manager

import eternalScript.api.manager.PluginStartable
import eternalScript.core.data.Config
import eternalScript.core.metrics.Metrics
import eternalScript.core.the.Root

object MetricsManager : PluginStartable {
    private const val PLUGIN_ID = 27192

    override fun start() {
        if (ConfigManager.value(Config.METRICS)) {
            Metrics(Root.INSTANCE, PLUGIN_ID)
        }
    }
}
