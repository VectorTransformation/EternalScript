package eternalScript.core.script.generation

/** Read-only projections of one atomic active-generation snapshot. */
internal class ActiveGenerationView(
    private val current: () -> ManagedProjectGeneration?
) {
    fun pluginDependencies(): Set<String> =
        current()?.runtime?.pluginDependencies.orEmpty()

    fun snapshot(): ScriptProjectGenerationSnapshot {
        val generation = current() ?: return ScriptProjectGenerationSnapshot.EMPTY
        return ScriptProjectGenerationSnapshot(
            state = generation.runtime.state,
            sourceNames = generation.sourceNames.toSet(),
            entryNames = generation.runtime.scripts
                .map { script -> script.javaClass.name }
                .sorted()
        )
    }
}
