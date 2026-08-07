package eternalScript.core.runtime

import eternalScript.EternalScript
import eternalScript.core.data.PluginPaths
import java.io.InputStream
import java.util.logging.Logger

internal const val PLUGIN_NAME = "EternalScript"

internal class PluginHost(
    val plugin: EternalScript
) {
    val logger: Logger
        get() = plugin.logger

    val paths = PluginPaths(plugin.dataFolder)

    fun resource(path: String): InputStream? = plugin.getResource(path)

    fun saveResource(path: String, replace: Boolean = false) {
        plugin.saveResource(path, replace)
    }
}
