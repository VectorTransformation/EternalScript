package eternalScript.core.script.project

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile

internal data class CompilerCachePruneBatch(
    val removed: List<Path>,
    val failures: Map<Path, String>
) {
    companion object {
        val EMPTY = CompilerCachePruneBatch(emptyList(), emptyMap())
    }
}

internal fun pruneCompilerCacheDirectory(
    directory: Path,
    maxFiles: Int,
    cutoff: Instant,
    retained: Set<Path>
): CompilerCachePruneBatch {
    if (!Files.isDirectory(directory)) return CompilerCachePruneBatch.EMPTY

    val files = Files.list(directory).use { paths ->
        paths.filter(Path::isRegularFile)
            .map { path ->
                CompilerCacheFile(
                    path = path.toAbsolutePath().normalize(),
                    modified = Files.getLastModifiedTime(path).toInstant()
                )
            }
            .sorted(
                compareByDescending<CompilerCacheFile>(CompilerCacheFile::modified)
                    .thenBy { file -> file.path.invariantSeparatorsPathString }
            )
            .toList()
    }
    val retainedCount = files.count { file -> file.path in retained }
    var available = (maxFiles - retainedCount).coerceAtLeast(0)
    val removed = mutableListOf<Path>()
    val failures = linkedMapOf<Path, String>()

    files.forEach { file ->
        if (file.path in retained) return@forEach
        val keep = file.modified >= cutoff && available > 0
        if (keep) {
            available -= 1
            return@forEach
        }
        try {
            if (Files.deleteIfExists(file.path)) {
                removed.add(file.path)
            }
        } catch (exception: Exception) {
            failures[file.path] = exception.message ?: exception.javaClass.name
        }
    }
    return CompilerCachePruneBatch(removed, failures)
}

private data class CompilerCacheFile(
    val path: Path,
    val modified: Instant
)
