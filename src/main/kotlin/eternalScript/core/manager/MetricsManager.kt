package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.core.data.Config
import eternalScript.core.metrics.Metrics
import eternalScript.core.the.Root

object MetricsManager : Manager {
    private const val PLUGIN_ID = 27192

    override fun register() {
        if (ConfigManager.value(Config.METRICS)) {
            Metrics(Root.instance(), PLUGIN_ID)
        }
    }
}