package eternalScript.core.script.generation

import eternalScript.core.script.project.ScriptProjectSource

/** Thin public-results facade over the generation lifecycle engine. */
internal class ScriptGenerationCoordinator(
    private val engine: GenerationLifecycleEngine
) {
    fun open() = engine.open()

    fun close() = engine.close()

    fun invalidateEnvironment() = engine.invalidateEnvironment()

    fun stop() = engine.stop()

    suspend fun load(project: ScriptProjectSource): ScriptProjectLoadResult =
        engine.load(project)

    suspend fun check(project: ScriptProjectSource): ScriptProjectCheckResult =
        engine.check(project)

    suspend fun clearNow(): ScriptProjectUnloadResult = engine.clearNow()

    fun activePluginDependencies(): Set<String> = engine.activePluginDependencies()

    fun freezeForDisabledPlugin(pluginName: String): Boolean =
        engine.freezeForDisabledPlugin(pluginName)

    suspend fun unloadForDisabledPlugins(pluginNames: Set<String>): Int? =
        engine.unloadForDisabledPlugins(pluginNames)

    fun generationSnapshot(): ScriptProjectGenerationSnapshot = engine.generationSnapshot()
}
