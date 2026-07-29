package eternalScript.core.script.definition

import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.manager.ConfigManager
import eternalScript.core.the.Root
import com.mojang.brigadier.Command
import kotlinx.coroutines.Job
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.cli.common.CLICompiler
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.IllegalCallableAccessException
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.util.classpathFromClassloader

internal enum class ClasspathEntryType {
    REGULAR_FILE,
    DIRECTORY,
    OTHER,
    MISSING
}

internal data class ClasspathEntrySnapshot(
    val classpathIndex: Int,
    val normalizedPath: String,
    val type: ClasspathEntryType,
    val size: Long,
    val lastModifiedSeconds: Long,
    val lastModifiedNanos: Int,
    val contentHash: String
)

private data class ClasspathFileState(
    val type: ClasspathEntryType,
    val size: Long,
    val lastModifiedSeconds: Long,
    val lastModifiedNanos: Int,
    val creationSeconds: Long,
    val creationNanos: Int,
    val fileKey: String
)

private data class CachedContentHash(
    val state: ClasspathFileState,
    val hash: String
)

private val classpathContentHashCache = ConcurrentHashMap<String, CachedContentHash>()

fun pluginClasspath(): List<File> {
    val loader = Root.classLoader(ConfigManager.value(Config.CLASS_LOADER))
        ?: Root.INSTANCE.javaClass.classLoader
    return buildSet {
        addAll(classpathFromClassloader(loader, true).orEmpty())
        runtimeClasspathAnchors()
            .filter(loader::resolvesSameClass)
            .mapNotNullTo(this, Class<*>::codeSourceFile)
    }.toList()
}

private fun ClassLoader.resolvesSameClass(type: Class<*>): Boolean =
    runCatching { loadClass(type.name) === type }.getOrDefault(false)

private fun Class<*>.codeSourceFile(): File? =
    runCatching {
        protectionDomain
            ?.codeSource
            ?.location
            ?.toClasspathFile()
    }.getOrNull()?.takeIf(File::exists)

internal fun URL.toClasspathFile(): File? = when (protocol) {
    "file" -> File(toURI())
    "jar" -> (openConnection() as? JarURLConnection)
        ?.jarFileURL
        ?.toClasspathFile()
    else -> null
}

fun libraryClasspath() = ConfigManager.value<List<String>>(Config.LIBS).flatMap { lib ->
    Resource.PLUGINS.child(lib).searchAllSequence({ it.extension == "jar" })
}

internal data class ScriptRuntimeClasspath(
    val files: List<File>,
    val libraryFiles: List<File>,
    val fingerprint: String
)

internal fun classpathSnapshot(entries: Iterable<ClasspathEntrySnapshot>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entries.forEach { entry ->
        digest.updateField(entry.classpathIndex.toString())
        digest.updateField(entry.normalizedPath)
        digest.updateField(entry.type.name)
        digest.updateField(entry.size.toString())
        digest.updateField(entry.lastModifiedSeconds.toString())
        digest.updateField(entry.lastModifiedNanos.toString())
        digest.updateField(entry.contentHash)
    }
    return digest.digest().toHexString()
}

private fun runtimeClasspathSnapshot(classpath: List<File>): String =
    classpathSnapshot(
        classpath.flatMapIndexed { index, file ->
            snapshotClasspathRoot(index, file.toPath().toAbsolutePath().normalize())
        }
    )

internal fun scriptRuntimeClasspath(): ScriptRuntimeClasspath {
    val libraryFiles = libraryClasspath()
        .distinctBy { file -> file.toPath().toAbsolutePath().normalize() }
    val files = buildSet {
        addAll(pluginClasspath())
        addAll(libraryFiles)
    }.toList()
    return ScriptRuntimeClasspath(
        files = files,
        libraryFiles = libraryFiles,
        fingerprint = runtimeClasspathSnapshot(files)
    )
}

private fun snapshotClasspathRoot(index: Int, root: Path): List<ClasspathEntrySnapshot> {
    val state = root.fileState() ?: return listOf(root.missingSnapshot(index))
    if (state.type != ClasspathEntryType.DIRECTORY) {
        return listOf(root.snapshot(index))
    }

    return Files.walk(root).use { paths ->
        paths.toList()
            .sortedBy(Path::normalizedClasspathPath)
            .map { path -> path.snapshot(index) }
    }
}

private fun Path.snapshot(index: Int): ClasspathEntrySnapshot {
    repeat(MAX_SNAPSHOT_ATTEMPTS) {
        val before = fileState() ?: return missingSnapshot(index)
        val contentHash = if (before.type == ClasspathEntryType.REGULAR_FILE) {
            contentHash(before)
        } else {
            ""
        }
        val after = fileState()
        if (before == after) {
            return ClasspathEntrySnapshot(
                classpathIndex = index,
                normalizedPath = normalizedClasspathPath(),
                type = before.type,
                size = before.size,
                lastModifiedSeconds = before.lastModifiedSeconds,
                lastModifiedNanos = before.lastModifiedNanos,
                contentHash = contentHash
            )
        }
        classpathContentHashCache.remove(normalizedClasspathPath())
    }
    error("Classpath entry changed while its cache fingerprint was being calculated: $this")
}

private fun Path.missingSnapshot(index: Int) = ClasspathEntrySnapshot(
    classpathIndex = index,
    normalizedPath = normalizedClasspathPath(),
    type = ClasspathEntryType.MISSING,
    size = -1,
    lastModifiedSeconds = 0,
    lastModifiedNanos = 0,
    contentHash = ""
)

private fun Path.fileState(): ClasspathFileState? {
    val attributes = try {
        Files.readAttributes(this, BasicFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
        return null
    }
    val modified = attributes.lastModifiedTime().toInstant()
    val created = attributes.creationTime().toInstant()
    val type = when {
        attributes.isRegularFile -> ClasspathEntryType.REGULAR_FILE
        attributes.isDirectory -> ClasspathEntryType.DIRECTORY
        else -> ClasspathEntryType.OTHER
    }
    return ClasspathFileState(
        type = type,
        size = attributes.size(),
        lastModifiedSeconds = modified.epochSecond,
        lastModifiedNanos = modified.nano,
        creationSeconds = created.epochSecond,
        creationNanos = created.nano,
        fileKey = attributes.fileKey()?.toString().orEmpty()
    )
}

private fun Path.contentHash(state: ClasspathFileState): String {
    val key = normalizedClasspathPath()
    return classpathContentHashCache.compute(key) { _, cached ->
        if (cached?.state == state) {
            cached
        } else {
            CachedContentHash(state, sha256())
        }
    }!!.hash
}

private fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHexString()
}

private fun Path.normalizedClasspathPath() =
    toAbsolutePath().normalize().toString().replace(File.separatorChar, '/')

private fun MessageDigest.updateField(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private const val MAX_SNAPSHOT_ATTEMPTS = 3
private const val HASH_BUFFER_SIZE = 64 * 1024

@OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
private fun runtimeClasspathAnchors() = listOf(
    Root.INSTANCE.javaClass,
    Unit::class.java,
    IllegalCallableAccessException::class.java,
    KSerializer::class.java,
    Json::class.java,
    ScriptCompilationConfiguration::class.java,
    JvmDependency::class.java,
    CLICompiler::class.java,
    KotlinToolchains::class.java,
    Job::class.java,
    Bukkit::class.java,
    Component::class.java,
    Command::class.java
)
