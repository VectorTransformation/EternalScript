package eternalScript.core.script.definition

import eternalScript.core.data.Resource
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.extension.toSHA256
import eternalScript.core.script.data.ScriptFile
import eternalScript.core.script.data.ScriptSuffix
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.script.experimental.api.SourceCode

private const val CACHE_SCHEMA = "5"
private const val CACHE_SCHEMA_FILE = ".schema"
private const val CACHE_GENERATION_FILE = ".generation"
private const val CACHE_CLEANUP_FILE = ".cleanup"
private const val IMPORT_MANIFEST_DIRECTORY = "_imports"
private const val MAX_CACHE_JARS = 128
private const val MAX_CACHE_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L

internal object ScriptImportCache {
    private var currentGeneration: String? = null
    private var prepared = false

    private val cacheRoot: File
        get() = Resource.CACHE.file

    private val manifestRoot: File
        get() = Resource.CACHE.child(IMPORT_MANIFEST_DIRECTORY)

    @Synchronized
    fun prepare() {
        if (prepared) return
        cacheRoot.mkdirs()
        val schema = Resource.CACHE.child(CACHE_SCHEMA_FILE)
        if (!schema.isFile || schema.readText() != CACHE_SCHEMA) {
            Resource.CACHE.clear()
            initialize()
        } else {
            cleanupPendingJars()
            manifestRoot.mkdirs()
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
        Resource.CACHE.clear()
        initialize()
        val lockedJars = cacheRoot.listFiles()
            ?.filter { it.isFile && it.extension == "jar" }
            .orEmpty()
        if (lockedJars.isNotEmpty()) {
            Resource.CACHE.child(CACHE_CLEANUP_FILE).writeText("")
        }
        prepared = true
    }

    @Synchronized
    fun generation(): String {
        prepare()
        return checkNotNull(currentGeneration)
    }

    @Synchronized
    fun record(source: SourceCode, paths: List<String>) {
        val name = source.name ?: return
        prepare()
        val content = (listOf(normalizeSourceName(name)) + paths)
            .joinToString("\n", transform = ::encode)
        manifest(name).writeText(content)
    }

    fun resolve(path: String): List<ScriptFile>? {
        val resource = Resource.SCRIPTS.child(path)
        if (!resource.exists()) return null

        return if (resource.isDirectory) {
            resource.searchAllSequence(
                { file -> ScriptSuffix.SCRIPT.check(file) }
            )
                .map(::ScriptFile)
                .sortedBy(ScriptFile::name)
                .toList()
        } else {
            listOf(ScriptFile(resource))
        }
    }

    @Synchronized
    fun fingerprint(source: SourceCode): String? {
        val name = source.name ?: return null
        val paths = readManifest(name)?.paths ?: return null
        if (paths.isEmpty()) return null

        val digest = MessageDigest.getInstance("SHA-256")
        appendDependencies(digest, name, paths, mutableSetOf())
        return digest.digest().toHexString()
    }

    @Synchronized
    fun dependents(path: String): Set<String> {
        prepare()
        val manifests = manifests()
        if (manifests.isEmpty()) return emptySet()

        val affected = resolve(path)
            ?.mapTo(mutableSetOf()) { normalizeSourceName(it.name) }
            ?: mutableSetOf(normalizeSourceName(path))
        var changed: Boolean

        do {
            changed = false
            manifests.forEach { manifest ->
                val dependencies = manifest.paths.flatMap { importedPath ->
                    resolve(importedPath)
                        ?.map { normalizeSourceName(it.name) }
                        ?: listOf(normalizeSourceName(importedPath))
                }
                if (dependencies.any(affected::contains) && affected.add(manifest.sourceName)) {
                    changed = true
                }
            }
        } while (changed)

        return affected - normalizeSourceName(path)
    }

    @Synchronized
    fun importPaths(): Set<String> {
        prepare()
        return manifests().flatMapTo(sortedSetOf()) { it.paths }
    }

    private fun appendDependencies(
        digest: MessageDigest,
        sourceName: String,
        paths: List<String>,
        visited: MutableSet<String>
    ) {
        if (!visited.add(normalizeSourceName(sourceName))) return

        paths.forEach { path ->
            digest.updateValue(path)
            val scripts = resolve(path)
            if (scripts == null) {
                digest.updateValue("<missing>")
                return@forEach
            }

            scripts.forEach { script ->
                digest.updateValue(script.name)
                digest.updateValue(script.fileSource.text)

                val importedSourceName = script.importSourceName()
                readManifest(importedSourceName)?.let { importedManifest ->
                    appendDependencies(digest, importedSourceName, importedManifest.paths, visited)
                }
            }
        }
    }

    private fun manifest(sourceName: String) =
        File(manifestRoot, "${normalizeSourceName(sourceName).toSHA256()}.imports")

    private fun initialize() {
        cacheRoot.mkdirs()
        Resource.CACHE.child(CACHE_SCHEMA_FILE).writeText(CACHE_SCHEMA)
        manifestRoot.mkdirs()
        newGeneration()
    }

    private fun newGeneration(): String {
        val generation = UUID.randomUUID().toString()
        generationFile().writeText(generation)
        currentGeneration = generation
        return generation
    }

    private fun generationFile() = Resource.CACHE.child(CACHE_GENERATION_FILE)

    private fun cleanupPendingJars() {
        val cleanup = Resource.CACHE.child(CACHE_CLEANUP_FILE)
        if (!cleanup.isFile) return

        cacheRoot.listFiles()
            ?.filter { it.isFile && it.extension == "jar" }
            ?.forEach(File::delete)
        val remainingJars = cacheRoot.listFiles()
            ?.any { it.isFile && it.extension == "jar" }
            ?: false
        if (!remainingJars) cleanup.delete()
    }

    private fun readManifest(sourceName: String): ImportManifest? {
        val file = manifest(sourceName)
        if (!file.isFile) return null
        return readManifest(file)
    }

    private fun readManifest(file: File): ImportManifest? {
        val values = runCatching {
            file.readLines().map(::decode)
        }.getOrNull() ?: return null
        val sourceName = values.firstOrNull() ?: return null
        return ImportManifest(sourceName, values.drop(1))
    }

    private fun manifests(): List<ImportManifest> =
        manifestRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "imports" }
            ?.mapNotNull(::readManifest)
            ?.toList()
            ?: emptyList()

    private fun pruneStaleJars() {
        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MILLIS
        cacheRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "jar" }
            ?.sortedByDescending(File::lastModified)
            ?.forEachIndexed { index, file ->
                if (index >= MAX_CACHE_JARS || file.lastModified() < cutoff) {
                    file.delete()
                }
            }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value))

    private fun normalizeSourceName(sourceName: String) = sourceName.removeSuffix(".kts")
}

private data class ImportManifest(
    val sourceName: String,
    val paths: List<String>
)

internal fun ScriptFile.importSourceName() = "$name.kts"

private fun MessageDigest.updateValue(value: String) {
    val bytes = value.toByteArray()
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}
