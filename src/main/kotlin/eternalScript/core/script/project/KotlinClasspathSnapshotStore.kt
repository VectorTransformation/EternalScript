@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package eternalScript.core.script.project

import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.classpathSnapshottingOperation
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal data class KotlinClasspathState(
    val entries: List<KotlinClasspathStateEntry>,
    val fingerprint: String
)

internal data class KotlinClasspathStateEntry(
    val path: Path,
    val fingerprint: String
)

internal class KotlinClasspathSnapshotStore(
    classpath: List<Path>,
    private val classpathIdentity: String,
    internal val snapshotsDirectory: Path
) {
    internal val classpath: List<Path> = classpath.map { path ->
        path.toAbsolutePath().normalize()
    }

    fun capture(): KotlinClasspathState {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField(classpathIdentity)
        val entries = classpath.mapIndexed { index, path ->
            require(Files.exists(path)) {
                "Compilation classpath entry does not exist: $path"
            }
            val fingerprint = path.contentFingerprint()
            digest.updateField(index.toString())
            digest.updateField(path.invariantSeparatorsPathString)
            digest.updateField(fingerprint)
            KotlinClasspathStateEntry(path, fingerprint)
        }
        return KotlinClasspathState(entries, digest.digest().toHexString())
    }

    fun requireUnchanged(expected: KotlinClasspathState) {
        val actual = capture()
        check(actual.fingerprint == expected.fingerprint) {
            "Compilation classpath changed while the Kotlin project was being compiled. " +
                "Try again after dependency updates finish."
        }
    }

    fun snapshots(
        state: KotlinClasspathState,
        session: KotlinToolchains.BuildSession,
        jvm: JvmPlatformToolchain
    ): List<Path> = state.entries.map { entry ->
        entry.snapshot(session, jvm)
    }

    fun prune(maxFiles: Int, cutoff: Instant): CompilerCachePruneBatch =
        pruneCompilerCacheDirectory(
            directory = snapshotsDirectory,
            maxFiles = maxFiles,
            cutoff = cutoff,
            retained = emptySet()
        )

    private fun KotlinClasspathStateEntry.snapshot(
        session: KotlinToolchains.BuildSession,
        jvm: JvmPlatformToolchain
    ): Path {
        val target = snapshotsDirectory.resolve("$fingerprint.snapshot")
        if (target.isRegularFile() && Files.size(target) > 0L) {
            return target
        }

        Files.createDirectories(snapshotsDirectory)
        val snapshot = session.executeOperation(jvm.classpathSnapshottingOperation(path))
        val temporary = Files.createTempFile(snapshotsDirectory, ".$fingerprint.", ".tmp")
        try {
            snapshot.saveSnapshot(temporary)
            check(path.contentFingerprint() == fingerprint) {
                "Compilation classpath entry changed while its Kotlin snapshot was " +
                    "being created: $path"
            }
            moveReplacingClasspathFile(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }
}

private fun Path.contentFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    if (isRegularFile()) {
        digest.updateField("file")
        digest.updateFile(this)
        return digest.digest().toHexString()
    }
    require(isDirectory()) {
        "Unsupported compilation classpath entry: $this"
    }
    digest.updateField("directory")
    Files.walk(this).use { paths ->
        paths.sorted(compareBy { path -> relativize(path).invariantSeparatorsPathString })
            .forEach { path ->
                val relative = relativize(path).invariantSeparatorsPathString
                when {
                    path.isDirectory() -> {
                        digest.updateField("directory")
                        digest.updateField(relative)
                    }

                    path.isRegularFile() -> {
                        digest.updateField("file")
                        digest.updateField(relative)
                        digest.updateFile(path)
                    }

                    else -> {
                        digest.updateField("other")
                        digest.updateField(relative)
                    }
                }
            }
    }
    return digest.digest().toHexString()
}

private fun MessageDigest.updateFile(path: Path) {
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(CLASSPATH_HASH_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            update(buffer, 0, count)
        }
    }
}

private fun MessageDigest.updateField(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private fun moveReplacingClasspathFile(source: Path, target: Path) {
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

private const val CLASSPATH_HASH_BUFFER_SIZE = 64 * 1024
