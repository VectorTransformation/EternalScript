package eternalScript.core.manager

import eternalScript.core.feedback.LocaleCatalog

internal class ReloadManager(
    private val config: ConfigManager,
    private val locales: LocaleCatalog
) : PluginStartable {
    override fun start() {
        reload()
    }

    fun reload() {
        config.reload()
        locales.reload()
    }
}
