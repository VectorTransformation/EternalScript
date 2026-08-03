package eternalScript.core.script.generation

import eternalScript.core.script.project.ScriptProjectSource

/** Transaction record for one complete source snapshot and its runtime. */
internal data class ManagedProjectGeneration(
    val project: ScriptProjectSource,
    val runtime: ScriptGeneration,
    val sourceNames: Set<String> = project.files.mapTo(linkedSetOf()) { it.name }
)
