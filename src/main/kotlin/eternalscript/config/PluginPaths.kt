package eternalscript.config

import org.bukkit.plugin.java.JavaPlugin
import java.io.File

internal class PluginPaths(plugin: JavaPlugin) {
    val dataDirectory: File = plugin.dataFolder.absoluteFile.normalize()
    val scriptsDirectory: File = File(dataDirectory, "scripts")
    val librariesDirectory: File = File(dataDirectory, "libs")
    val languagesDirectory: File = File(dataDirectory, "lang")
    val cacheDirectory: File = File(dataDirectory, "cache")
    val scriptCacheV5Directory: File = File(cacheDirectory, "scripts-v5")
    val ideDirectory: File = File(dataDirectory, ".eternalscript/ide")
    val configFile: File = File(dataDirectory, "config.yml")

    fun ensureBaseDirectories() {
        listOf(dataDirectory, librariesDirectory, cacheDirectory, ideDirectory).forEach { directory ->
            check(directory.mkdirs() || directory.isDirectory) {
                "Could not create plugin directory: ${directory.absolutePath}"
            }
        }
    }
}
