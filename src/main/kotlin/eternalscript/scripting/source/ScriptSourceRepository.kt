package eternalscript.scripting.source

import eternalscript.config.PluginPaths
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.zip.ZipFile

internal class ScriptSourceRepository(
    private val plugin: JavaPlugin,
    private val paths: PluginPaths
) {
    @Volatile
    private var knownScriptPaths: List<String> = emptyList()

    fun installBundledResources() {
        installDirectory("scripts", paths.scriptsDirectory) { name ->
            name.endsWith(".$ETERNAL_SCRIPT_EXTENSION")
        }
        ensureDirectory(paths.scriptsDirectory)
        ensureDirectory(paths.languagesDirectory)
    }

    fun all(): List<ScriptSourceFile> {
        val result = readScriptSources(paths.scriptsDirectory)
        refreshKnownPaths()
        return result
    }

    fun knownPaths(): List<String> = knownScriptPaths

    fun validate(path: String): ScriptPathResult = validateScriptPath(path)

    fun prepareLoad(path: String): ScriptTargetPreparation =
        prepareScriptLoadTarget(paths.scriptsDirectory, path)

    fun prepareUnload(path: String): ScriptTargetPreparation =
        prepareScriptUnloadTarget(paths.scriptsDirectory, path)

    fun refreshKnownPaths() {
        knownScriptPaths = discoverScriptTargets(paths.scriptsDirectory)
    }

    private fun installDirectory(
        resourceRoot: String,
        target: File,
        include: (String) -> Boolean
    ) {
        if (target.exists()) {
            check(target.isDirectory) { "Plugin path is not a directory: ${target.absolutePath}" }
            return
        }
        val artifact = runCatching {
            File(plugin.javaClass.protectionDomain.codeSource.location.toURI())
        }.getOrNull() ?: return
        if (!artifact.isFile) return

        ZipFile(artifact).use { zip ->
            zip.entries().asSequence()
                .map { entry -> entry.name }
                .filter { name -> name.startsWith("$resourceRoot/") && include(name) }
                .forEach { name -> plugin.saveResource(name, false) }
        }
    }

    private fun ensureDirectory(directory: File) {
        check(directory.mkdirs() || directory.isDirectory) {
            "Could not create plugin directory: ${directory.absolutePath}"
        }
    }
}
