package eternalScript.core.script.project

import eternalScript.core.data.Resource
import eternalScript.core.extension.relativize
import eternalScript.core.script.data.ScriptPrefix

internal enum class ScriptProjectEntryKind {
    SOURCE,
    LEGACY
}

internal data class ScriptProjectEntry(
    val path: String,
    val kind: ScriptProjectEntryKind,
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

    fun legacyPaths(): List<String> = entries(ScriptProjectEntryKind.LEGACY).keys.toList()

    private fun sourceEntries() = entries(ScriptProjectEntryKind.SOURCE)

    private fun entries(kind: ScriptProjectEntryKind): Map<String, ScriptProjectEntry> =
        discover()
            .asSequence()
            .filter { entry -> entry.kind == kind }
            .sortedWith(compareBy<ScriptProjectEntry> { it.path.lowercase() }.thenBy { it.path })
            .associateByTo(linkedMapOf(), ScriptProjectEntry::path)
}

internal fun runtimeScriptProjectRepository() = ScriptProjectRepository(discover = {
    val sources = Resource.SCRIPTS.searchAllSequence(
        { file ->
            isRuntimeScriptPath(file.relativize(Resource.SCRIPTS))
        },
        { directory -> !ScriptPrefix.IGNORE.check(directory) }
    ).map { file ->
        ScriptProjectEntry(
            path = file.relativize(Resource.SCRIPTS),
            kind = ScriptProjectEntryKind.SOURCE,
            readText = file::readText
        )
    }
    val legacy = Resource.SCRIPTS.searchAllSequence(
        { file -> file.isLegacyScript() }
    ).map { file ->
        ScriptProjectEntry(
            path = file.relativize(Resource.SCRIPTS),
            kind = ScriptProjectEntryKind.LEGACY,
            readText = file::readText
        )
    }
    (sources + legacy).toList()
})

private fun java.io.File.isLegacyScript() =
    isLegacyScriptPath(name)

internal fun isLegacyScriptPath(path: String) =
    path.endsWith(".kts", ignoreCase = true)

fun isRuntimeScriptPath(path: String): Boolean {
    val parts = path.replace('\\', '/').split('/')
    return path.endsWith(".kt") &&
        parts.none { part -> part.startsWith("-") }
}

internal fun legacyScriptWarning(paths: List<String>): String? {
    if (paths.isEmpty()) return null

    val preview = paths.take(LEGACY_WARNING_PREVIEW_SIZE).joinToString()
    val remainder = (paths.size - LEGACY_WARNING_PREVIEW_SIZE).coerceAtLeast(0)
    val suffix = if (remainder > 0) " and $remainder more" else ""
    return "Ignored ${paths.size} legacy .kts/.eternal.kts script source(s): $preview$suffix. " +
        "Project mode only loads *.kt; migrate these files before reloading."
}

private const val DEFAULT_SNAPSHOT_ATTEMPTS = 3
private const val LEGACY_WARNING_PREVIEW_SIZE = 5
