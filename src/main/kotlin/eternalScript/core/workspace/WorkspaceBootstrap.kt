package eternalScript.core.workspace

import eternalScript.core.data.PluginPath
import eternalScript.core.data.PluginPaths
import eternalScript.core.runtime.PluginHost
import eternalScript.core.script.data.ScriptSuffix
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Creates the plugin-owned workspace and restores bundled resources.
 *
 * Project execution remains owned by DataManager/ScriptManager; this object
 * only performs the one-time workspace bootstrap and returns its diagnostics.
 */
internal class WorkspaceBootstrap(
    private val host: PluginHost,
    private val paths: PluginPaths,
    private val workspace: WorkspaceManager
) {
    fun initialize(): WorkspaceUpdateResult {
        listOf(
            paths.dataFolder,
            paths.libs,
            paths.cache
        ).forEach(PluginPath::make)

        saveResource(paths.scripts, *ScriptSuffix.SCRIPT.suffix)
        saveResource(paths.lang, *ScriptSuffix.LANG.suffix)

        return workspace.initialize(paths.dataFolder.toPath())
    }

    private fun saveResource(resource: PluginPath, vararg extension: String) {
        if (resource.exists()) return

        val jarFile = File(javaClass.protectionDomain.codeSource.location.toURI())
        val fileName = resource.file.nameWithoutExtension
        ZipFile(jarFile).use { jar ->
            jar.entries()
                .asSequence()
                .map(ZipEntry::getName)
                .filter { name ->
                    name.startsWith(fileName) && extension.any(name::endsWith)
                }
                .forEach { name ->
                    host.saveResource(name, false)
                }
        }
    }
}
