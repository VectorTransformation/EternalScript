package eternalScript.core.script.definition

import java.io.File
import java.nio.file.Path
import java.util.UUID

private const val CACHE_SCHEMA = "9"
private const val CACHE_SCHEMA_FILE = ".schema"
private const val CACHE_GENERATION_FILE = ".generation"
private const val CACHE_CLEANUP_FILE = ".cleanup"
private const val MAX_CACHE_JARS = 128
private const val MAX_CACHE_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L

internal class ScriptCompilationCache(
    cacheRoot: File
) {
    private val storage = ScriptCompilationCacheStorage(cacheRoot)

    fun prepare() = storage.prepare()

    fun reset() = storage.reset()

    fun retain(file: File) = storage.retain(file)

    fun release(file: File) = storage.release(file)

    fun retainedGenerationJars(): Set<Path> = storage.retainedGenerationJars()

    fun generation() = storage.generation()
}

internal class ScriptCompilationCacheStorage(
    private val cacheRoot: File
) {
    private var currentGeneration: String? = null
    private var prepared = false
    private val retainedJars = mutableMapOf<Path, Int>()

    @Synchronized
    fun prepare() {
        if (prepared) return
        cacheRoot.mkdirs()
        val schema = cacheRoot.resolve(CACHE_SCHEMA_FILE)
        if (!schema.isFile || schema.readText() != CACHE_SCHEMA) {
            clearCachePreservingRetainedJars()
            initialize()
        } else {
            cleanupPendingJars()
            currentGeneration = generationFile()
                .takeIf(File::isFile)
                ?.readText()
                ?.takeIf(String::isNotBlank)
                ?: newGeneration()
        }
        pruneStaleJars()
        prepared = true
    }

    @Synchronized
    fun reset() {
        clearCachePreservingRetainedJars()
        initialize()
        val lockedJars = cacheJars()
        if (lockedJars.isNotEmpty()) {
            cacheRoot.resolve(CACHE_CLEANUP_FILE).writeText("")
        }
        prepared = true
    }

    @Synchronized
    fun retain(file: File) {
        val path = file.normalizedCachePath()
        retainedJars[path] = retainedJars.getOrDefault(path, 0) + 1
    }

    @Synchronized
    fun release(file: File) {
        val path = file.normalizedCachePath()
        when (val references = retainedJars[path] ?: return) {
            1 -> retainedJars.remove(path)
            else -> retainedJars[path] = references - 1
        }
    }

    @Synchronized
    fun retainedGenerationJars(): Set<Path> =
        retainedJars.keys.toSet()

    @Synchronized
    fun generation(): String {
        prepare()
        return checkNotNull(currentGeneration)
    }

    private fun initialize() {
        cacheRoot.mkdirs()
        cacheRoot.resolve(CACHE_SCHEMA_FILE).writeText(CACHE_SCHEMA)
        newGeneration()
    }

    private fun newGeneration(): String {
        val generation = UUID.randomUUID().toString()
        generationFile().writeText(generation)
        currentGeneration = generation
        return generation
    }

    private fun generationFile() = cacheRoot.resolve(CACHE_GENERATION_FILE)

    private fun cleanupPendingJars() {
        val cleanup = cacheRoot.resolve(CACHE_CLEANUP_FILE)
        if (!cleanup.isFile) return

        cacheJars()
            .filterNot { file -> file.isRetained() }
            .forEach(File::delete)
        val remainingJars = cacheJars().isNotEmpty()
        if (!remainingJars) {
            cleanup.delete()
            removeEmptyCacheDirectories()
        }
    }

    private fun pruneStaleJars() {
        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MILLIS
        cacheJars()
            .filterNot { file -> file.isRetained() }
            .sortedByDescending(File::lastModified)
            .forEachIndexed { index, file ->
                if (index >= MAX_CACHE_JARS || file.lastModified() < cutoff) {
                    file.delete()
                }
            }
    }

    private fun cacheJars(): List<File> =
        if (cacheRoot.isDirectory) {
            cacheRoot.walkTopDown()
                .filter { file -> file.isFile && file.extension == "jar" }
                .toList()
        } else {
            emptyList()
        }

    private fun removeEmptyCacheDirectories() {
        cacheRoot.walkBottomUp()
            .filter { directory ->
                directory != cacheRoot &&
                    directory.isDirectory &&
                    directory.list().isNullOrEmpty()
            }
            .forEach(File::delete)
    }

    private fun clearCachePreservingRetainedJars() {
        if (!cacheRoot.isDirectory) return
        cacheRoot.walkBottomUp().forEach { file ->
            when {
                file == cacheRoot -> Unit
                file.isFile && !file.isRetained() -> file.delete()
                file.isDirectory && file.list().isNullOrEmpty() -> file.delete()
            }
        }
    }

    private fun File.isRetained(): Boolean =
        retainedJars.getOrDefault(normalizedCachePath(), 0) > 0

    private fun File.normalizedCachePath(): Path =
        toPath().toAbsolutePath().normalize()
}
