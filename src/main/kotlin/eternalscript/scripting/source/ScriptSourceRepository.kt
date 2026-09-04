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

    @Volatile
    private var knownScriptTargets: List<DiscoveredScriptTarget> = emptyList()

    fun installBundledResources() {
        installDirectory("scripts", paths.scriptsDirectory) { name ->
            name.endsWith(".$ETERNAL_SCRIPT_EXTENSION")
        }
        ensureDirectory(paths.scriptsDirectory)
        ensureDirectory(paths.languagesDirectory)
    }

    fun all(): List<ScriptSourceFile> {
        val scan = scanScriptSources(paths.scriptsDirectory)
        updateKnownTargets(scan.targets)
        return scan.sources
    }

    fun knownPaths(): List<String> {
        refreshKnownPaths()
        return knownScriptPaths
    }

    fun knownTargets(): List<DiscoveredScriptTarget> {
        refreshKnownPaths()
        return knownScriptTargets
    }

    fun validate(path: String): ScriptPathResult = validateScriptPath(path)

    fun prepareLoad(path: String): ScriptTargetPreparation =
        prepareScriptLoadTarget(paths.scriptsDirectory, path)

    fun prepareUnload(path: String): ScriptTargetPreparation =
        prepareScriptUnloadTarget(paths.scriptsDirectory, path)

    fun prepareEnabled(path: String): ScriptTargetPreparation {
        val canonical = when (val validation = validateScriptTargetPath(path)) {
            is ScriptPathResult.Valid -> validation.path
            is ScriptPathResult.Invalid -> return ScriptTargetPreparation.Invalid(validation.reason)
        }
        val target = knownTargets().firstOrNull { it.path == canonical && it.enabled }
            ?: return ScriptTargetPreparation.NotFound(canonical)
        return ScriptTargetPreparation.Ready(
            ScriptPathTransition(ScriptTarget(target.path, target.kind), null, null)
        )
    }

    fun refreshKnownPaths() {
        updateKnownTargets(discoverScriptTargetEntries(paths.scriptsDirectory))
    }

    private fun updateKnownTargets(targets: List<DiscoveredScriptTarget>) {
        knownScriptTargets = targets
        knownScriptPaths = knownScriptTargets.map(DiscoveredScriptTarget::path)
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
