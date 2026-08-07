package eternalScript.core.manager

import eternalScript.core.script.generation.ScriptGenerationCoordinator
import eternalScript.core.script.generation.ScriptProjectCheckResult
import eternalScript.core.script.generation.ScriptProjectGenerationSnapshot
import eternalScript.core.script.generation.ScriptProjectLoadResult
import eternalScript.core.script.generation.ScriptProjectUnloadResult
import eternalScript.core.script.project.ScriptProjectSource

/**
 * Public script API facade. Generation compilation, activation, replacement,
 * and disposal live in [ScriptGenerationCoordinator]; this object exposes
 * current project state to commands and scripts.
 */
internal object ScriptManager : PluginStoppable {
    private val generation = ScriptGenerationCoordinator()

    internal fun open() = generation.open()

    internal fun close() = generation.close()

    internal fun invalidateEnvironment() = generation.invalidateEnvironment()

    override fun stop() = generation.stop()

    internal suspend fun load(project: ScriptProjectSource): ScriptProjectLoadResult =
        generation.load(project)

    internal suspend fun check(project: ScriptProjectSource): ScriptProjectCheckResult =
        generation.check(project)

    internal suspend fun clearNow(): ScriptProjectUnloadResult = generation.clearNow()

    internal fun activePluginDependencies(): Set<String> =
        generation.activePluginDependencies()

    internal fun freezeForDisabledPlugin(pluginName: String): Boolean =
        generation.freezeForDisabledPlugin(pluginName)

    internal suspend fun unloadForDisabledPlugins(pluginNames: Set<String>): Int? =
        generation.unloadForDisabledPlugins(pluginNames)

    internal fun generationSnapshot(): ScriptProjectGenerationSnapshot =
        generation.generationSnapshot()
}
