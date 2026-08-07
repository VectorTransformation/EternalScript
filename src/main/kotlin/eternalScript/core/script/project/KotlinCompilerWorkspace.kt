package eternalScript.core.script.project

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal data class KotlinSourceChanges(
    val sources: List<Path>,
    val modified: List<Path>,
    val removed: List<Path>,
    val currentState: Map<String, String>
)

internal class KotlinCompilerWorkspace(cacheRoot: Path) {
    internal val root: Path =
        cacheRoot.toAbsolutePath().normalize().resolve(CACHE_NAMESPACE)
    internal val generatedSourcesDirectory: Path = root.resolve("sources")
    internal val classesDirectory: Path = root.resolve("classes")
    internal val classpathSnapshotsDirectory: Path = root.resolve("classpath-snapshots")
    internal val artifactsDirectory: Path = root.resolve("artifacts")
    internal val incrementalDirectory: Path = root.resolve("incremental")

    private val compiledSourceStateFile = root.resolve("compiled-sources.tsv")

    fun prepare() {
        Files.createDirectories(generatedSourcesDirectory)
        Files.createDirectories(classesDirectory)
        Files.createDirectories(classpathSnapshotsDirectory)
        Files.createDirectories(artifactsDirectory)
        Files.createDirectories(incrementalDirectory)
    }

    fun syncSources(module: ScriptProjectModule): KotlinSourceChanges {
        val previousState = readCompiledSourceState()
            ?: physicalSourceNames().associateWith { UNCOMPILED_SOURCE }
        val expected = module.files.associate { file ->
            val target = generatedSourcePath(file.name)
            val relativeName = target.relativeSourceName()
            relativeName to ExpectedSource(target, file.text)
        }
        check(expected.size == module.files.size) {
            "Generated Kotlin source paths must be unique."
        }
        expected.values.forEach { source ->
            writeIfChanged(source.path, source.text)
        }

        val expectedPaths = expected.values.mapTo(linkedSetOf(), ExpectedSource::path)
        val staleFiles = physicalSourcePaths().filter { path -> path !in expectedPaths }
        try {
            staleFiles.forEach(Files::delete)
        } finally {
            removeEmptySourceDirectories()
        }

        val currentState = expected.mapValues { (_, source) -> source.text.sha256() }
        val modified = currentState.entries
            .filter { (name, hash) -> previousState[name] != hash }
            .map { (name, _) -> generatedSourcePath(name) }
        val removed = previousState.keys
            .filter { name -> name !in currentState }
            .map(::generatedSourcePath)
        return KotlinSourceChanges(
            sources = expected.values.map(ExpectedSource::path),
            modified = modified,
            removed = removed,
            currentState = currentState
        )
    }

    fun persistCompiledSourceState(state: Map<String, String>) {
        val text = buildString {
            appendLine(SOURCE_STATE_SCHEMA)
            state.toSortedMap().forEach { (name, hash) ->
                check('\t' !in name && '\n' !in name && '\r' !in name)
                append(name)
                append('\t')
                appendLine(hash)
            }
        }
        writeIfChanged(compiledSourceStateFile, text)
    }

    internal fun generatedSourcePath(relativeName: String): Path {
        val relative = Path.of(relativeName)
        require(
            !relative.isAbsolute &&
                relative.nameCount > 0 &&
                relative.none { segment ->
                    val value = segment.toString()
                    value == "." || value == ".."
                }
        ) {
            "Generated source path must be a normalized relative path: $relativeName"
        }
        val target = generatedSourcesDirectory.resolve(relative).normalize()
        require(
            target != generatedSourcesDirectory && target.startsWith(generatedSourcesDirectory)
        ) {
            "Generated source path escapes the source cache: $relativeName"
        }
        return target
    }

    private fun Path.relativeSourceName(): String {
        val normalized = toAbsolutePath().normalize()
        require(
            normalized != generatedSourcesDirectory &&
                normalized.startsWith(generatedSourcesDirectory)
        ) {
            "Generated source is outside the source cache: $this"
        }
        return generatedSourcesDirectory.relativize(normalized)
            .invariantSeparatorsPathString
    }

    private fun physicalSourcePaths(): List<Path> {
        if (!Files.isDirectory(generatedSourcesDirectory)) return emptyList()
        return Files.walk(generatedSourcesDirectory).use { paths ->
            paths.filter(Path::isRegularFile)
                .map { path -> path.toAbsolutePath().normalize() }
                .sorted()
                .toList()
        }
    }

    private fun physicalSourceNames(): List<String> =
        physicalSourcePaths().map { path -> path.relativeSourceName() }

    private fun removeEmptySourceDirectories() {
        if (!Files.isDirectory(generatedSourcesDirectory)) return
        val directories = Files.walk(generatedSourcesDirectory).use { paths ->
            paths.filter(Path::isDirectory)
                .filter { path -> path != generatedSourcesDirectory }
                .sorted(
                    compareByDescending<Path>(Path::getNameCount)
                        .thenByDescending { path -> path.invariantSeparatorsPathString }
                )
                .toList()
        }
        directories.forEach { directory ->
            runCatching { Files.deleteIfExists(directory) }
        }
    }

    private fun readCompiledSourceState(): Map<String, String>? {
        if (!compiledSourceStateFile.isRegularFile()) return null
        return runCatching {
            val lines = Files.readAllLines(compiledSourceStateFile, StandardCharsets.UTF_8)
            check(lines.firstOrNull() == SOURCE_STATE_SCHEMA)
            lines.drop(1).associate { line ->
                val separator = line.indexOf('\t')
                check(separator > 0 && separator < line.lastIndex)
                line.substring(0, separator) to line.substring(separator + 1)
            }
        }.getOrNull()
    }

    private fun writeIfChanged(target: Path, text: String): Boolean {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (
            target.isRegularFile() &&
            Files.size(target) == bytes.size.toLong() &&
            Files.readAllBytes(target).contentEquals(bytes)
        ) {
            return false
        }

        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING)
            moveReplacingWorkspaceFile(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return true
    }

    private data class ExpectedSource(
        val path: Path,
        val text: String
    )

    private companion object {
        private const val CACHE_NAMESPACE = "kotlin-incremental-v1"
        private const val SOURCE_STATE_SCHEMA = "eternal-script-compiled-sources-v2"
        private const val UNCOMPILED_SOURCE = "<uncompiled>"
    }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .toHexString()

private fun moveReplacingWorkspaceFile(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
