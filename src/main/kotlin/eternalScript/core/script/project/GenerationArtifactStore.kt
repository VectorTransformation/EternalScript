package eternalScript.core.script.project

import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile

internal class GenerationArtifactStore(
    internal val artifactsDirectory: Path,
    private val classesDirectory: Path,
    private val compilerVersion: String
) {
    fun artifact(module: ScriptProjectModule, classpathFingerprint: String): Path =
        artifactsDirectory.resolve("${artifactKey(module, classpathFingerprint)}.jar")

    fun isUsable(artifact: Path, module: ScriptProjectModule): Boolean =
        artifact.isUsableGenerationJar(module)

    fun packageGeneration(target: Path, module: ScriptProjectModule): Path {
        val contents = Files.walk(classesDirectory).use { paths ->
            paths.filter(Path::isRegularFile)
                .sorted(
                    compareBy { path ->
                        classesDirectory.relativize(path).invariantSeparatorsPathString
                    }
                )
                .map { path ->
                    GenerationJarContent(
                        name = classesDirectory.relativize(path)
                            .invariantSeparatorsPathString,
                        bytes = Files.readAllBytes(path)
                    )
                }
                .toList()
        }
        check(contents.none { content -> content.name == GENERATION_INDEX_ENTRY }) {
            "Compiler output reserved the EternalScript generation index path."
        }
        val entries = (
            contents + GenerationJarContent(
                name = GENERATION_INDEX_ENTRY,
                bytes = generationIndex(module, contents)
            )
        ).sortedBy(GenerationJarContent::name)
        Files.createDirectories(artifactsDirectory)
        val temporary = Files.createTempFile(
            artifactsDirectory,
            ".${target.fileName}.",
            ".tmp"
        )
        try {
            JarOutputStream(
                BufferedOutputStream(
                    Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)
                )
            ).use { jar ->
                entries.forEach { content -> jar.writeStoredEntry(content) }
            }
            publishArtifact(temporary, target, module)
        } finally {
            Files.deleteIfExists(temporary)
        }
        check(target.isUsableGenerationJar(module)) {
            "Generation JAR was not readable after publication: $target"
        }
        return target
    }

    fun prune(
        retained: Set<Path>,
        maxFiles: Int,
        cutoff: Instant
    ): CompilerCachePruneBatch = pruneCompilerCacheDirectory(
        directory = artifactsDirectory,
        maxFiles = maxFiles,
        cutoff = cutoff,
        retained = retained
    )

    private fun artifactKey(
        module: ScriptProjectModule,
        classpathFingerprint: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField(CACHE_SCHEMA)
        digest.updateField(compilerVersion)
        digest.updateField(MODULE_NAME)
        digest.updateField(JvmTarget.JVM_25.stringValue)
        digest.updateField(module.fingerprint)
        digest.updateField(classpathFingerprint)
        return digest.digest().toHexString()
    }

    private fun publishArtifact(
        temporary: Path,
        target: Path,
        module: ScriptProjectModule
    ) {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            if (!target.isUsableGenerationJar(module)) {
                moveReplacingArtifact(temporary, target)
            }
        } catch (_: AtomicMoveNotSupportedException) {
            moveReplacingArtifact(temporary, target)
        }
    }

    private fun Path.isUsableGenerationJar(module: ScriptProjectModule): Boolean {
        if (!isRegularFile() || runCatching { Files.size(this) }.getOrDefault(0L) <= 0L) {
            return false
        }
        return runCatching {
            JarFile(toFile(), false).use { jar ->
                val indexEntry = jar.getJarEntry(GENERATION_INDEX_ENTRY)
                    ?: return@use false
                if (indexEntry.size !in 1L..MAX_GENERATION_INDEX_BYTES) {
                    return@use false
                }
                val index = jar.getInputStream(indexEntry).bufferedReader(
                    StandardCharsets.UTF_8
                ).use { reader ->
                    parseGenerationIndex(reader.readLines(), module)
                }
                val actualNames = jar.entries().asSequence()
                    .filterNot { entry -> entry.isDirectory }
                    .map(JarEntry::getName)
                    .filterNot { name -> name == GENERATION_INDEX_ENTRY }
                    .sorted()
                    .toList()
                if (actualNames != index.map(GenerationJarIndexRecord::name)) {
                    return@use false
                }
                index.all { record ->
                    val entry = jar.getJarEntry(record.name) ?: return@all false
                    if (entry.size != record.size) return@all false
                    val digest = jar.getInputStream(entry).use { input ->
                        val messageDigest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(ARTIFACT_HASH_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            messageDigest.update(buffer, 0, count)
                        }
                        messageDigest.digest().toHexString()
                    }
                    digest == record.sha256
                }
            }
        }.getOrDefault(false)
    }

    private fun generationIndex(
        module: ScriptProjectModule,
        contents: List<GenerationJarContent>
    ): ByteArray = buildString {
        appendLine(GENERATION_INDEX_SCHEMA)
        appendLine(module.fingerprint)
        contents.sortedBy(GenerationJarContent::name).forEach { content ->
            append(content.bytes.sha256())
            append('\t')
            append(content.bytes.size)
            append('\t')
            appendLine(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    content.name.toByteArray(StandardCharsets.UTF_8)
                )
            )
        }
    }.toByteArray(StandardCharsets.UTF_8)

    private fun parseGenerationIndex(
        lines: List<String>,
        module: ScriptProjectModule
    ): List<GenerationJarIndexRecord> {
        check(lines.size >= 2 && lines[0] == GENERATION_INDEX_SCHEMA)
        check(lines[1] == module.fingerprint)
        val records = lines.drop(2).map { line ->
            val fields = line.split('\t', limit = 3)
            check(fields.size == 3)
            val sha256 = fields[0]
            check(SHA256_HEX.matches(sha256))
            val size = fields[1].toLong()
            check(size >= 0L)
            val name = Base64.getUrlDecoder().decode(fields[2])
                .toString(StandardCharsets.UTF_8)
            check(name.isNotEmpty() && name != GENERATION_INDEX_ENTRY)
            GenerationJarIndexRecord(name, size, sha256)
        }.sortedBy(GenerationJarIndexRecord::name)
        check(records.map(GenerationJarIndexRecord::name).distinct().size == records.size)
        return records
    }

    private fun JarOutputStream.writeStoredEntry(content: GenerationJarContent) {
        val crc = CRC32().apply { update(content.bytes) }
        val entry = JarEntry(content.name).apply {
            method = JarEntry.STORED
            size = content.bytes.size.toLong()
            compressedSize = content.bytes.size.toLong()
            this.crc = crc.value
            time = DETERMINISTIC_JAR_TIMESTAMP
        }
        putNextEntry(entry)
        write(content.bytes)
        closeEntry()
    }

    private data class GenerationJarContent(
        val name: String,
        val bytes: ByteArray
    )

    private data class GenerationJarIndexRecord(
        val name: String,
        val size: Long,
        val sha256: String
    )

    private companion object {
        private const val CACHE_SCHEMA = "eternal-script-kotlin-incremental-v2"
        private const val MODULE_NAME = "eternal-script-project"
        private const val DETERMINISTIC_JAR_TIMESTAMP = 0L
        private const val GENERATION_INDEX_ENTRY =
            "META-INF/eternalscript-generation-index.tsv"
        private const val GENERATION_INDEX_SCHEMA =
            "eternal-script-generation-index-v1"
        private const val MAX_GENERATION_INDEX_BYTES = 16L * 1024L * 1024L
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHexString()

private fun MessageDigest.updateField(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private fun moveReplacingArtifact(source: Path, target: Path) {
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

private const val ARTIFACT_HASH_BUFFER_SIZE = 64 * 1024
