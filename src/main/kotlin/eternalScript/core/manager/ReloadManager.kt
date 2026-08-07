package eternalScript.core.manager

import eternalScript.core.feedback.LocaleCatalog

object ReloadManager : PluginStartable {
    override fun start() {
        reload()
    }

    fun reload() {
        ConfigManager.reload()
        LocaleCatalog.reload()
    }
}
