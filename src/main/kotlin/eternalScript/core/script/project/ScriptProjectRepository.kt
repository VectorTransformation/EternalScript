package eternalScript.core.script.project

import eternalScript.core.data.PluginPaths
import eternalScript.core.extension.relativize
import eternalScript.core.script.data.ScriptPrefix

internal data class ScriptProjectEntry(
    val path: String,
    val readText: () -> String
)

/**
 * Creates a stable, immutable source snapshot from a changing script directory.
 *
 * Discovery and reads are injected so the consistency protocol can be tested
 * without a running Paper server or filesystem fixtures.
 */
internal class ScriptProjectRepository(
    private val discover: () -> List<ScriptProjectEntry>,
    private val snapshotAttempts: Int = DEFAULT_SNAPSHOT_ATTEMPTS
) {
    init {
        require(snapshotAttempts > 0) {
            "Snapshot attempts must be positive."
        }
    }

    fun snapshot(): ScriptProjectSource? {
        var emptySnapshotSeen = false

        repeat(snapshotAttempts) {
            val before = sourceEntries()
            if (before.isEmpty()) {
                if (emptySnapshotSeen) return null
                emptySnapshotSeen = true
                return@repeat
            }
            emptySnapshotSeen = false

            val projectFiles = runCatching {
                before.values.map { entry ->
                    ScriptProjectFile(entry.path, entry.readText())
                }
            }.getOrNull() ?: return@repeat

            val after = sourceEntries()
            if (before.keys != after.keys) return@repeat

            val stable = projectFiles.all { source ->
                val entry = after[source.name] ?: return@all false
                runCatching { entry.readText() == source.text }.getOrDefault(false)
            }
            if (stable) {
                return ScriptProjectSource.compose(projectFiles)
            }
        }

        error("Script files changed while the project snapshot was being created. Try again.")
    }

    fun paths(): List<String> = sourceEntries().keys.toList()

    private fun sourceEntries(): Map<String, ScriptProjectEntry> =
        discover()
            .asSequence()
            .sortedWith(compareBy<ScriptProjectEntry> { it.path.lowercase() }.thenBy { it.path })
            .associateByTo(linkedMapOf(), ScriptProjectEntry::path)
}

internal fun runtimeScriptProjectRepository(paths: PluginPaths) = ScriptProjectRepository(discover = {
    val sources = paths.scripts.searchAllSequence(
        { file ->
            isRuntimeScriptPath(file.relativize(paths.scripts))
        },
        { directory -> !ScriptPrefix.IGNORE.check(directory) }
    ).map { file ->
        ScriptProjectEntry(
            path = file.relativize(paths.scripts),
            readText = file::readText
        )
    }
    sources.toList()
})

fun isRuntimeScriptPath(path: String): Boolean {
    val parts = path.replace('\\', '/').split('/')
    return path.endsWith(".kt") &&
        parts.none { part -> part.startsWith("-") }
}

private const val DEFAULT_SNAPSHOT_ATTEMPTS = 3
