package eternalscript.scripting.repl.k2

import eternalscript.util.Sha256
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

internal class CompiledComponentArtifact private constructor(
    val jar: Path,
    val ownedClasses: Set<String>,
    val hash: String
) : AutoCloseable {
    private val references = AtomicInteger(1)

    fun retain(): CompiledComponentArtifact {
        while (true) {
            val current = references.get()
            check(current > 0) { "A disposed component artifact cannot be retained: $jar" }
            if (references.compareAndSet(current, current + 1)) return this
        }
    }

    override fun close() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "A component artifact was closed more than once: $jar" }
        if (remaining == 0) runCatching { Files.deleteIfExists(jar) }
    }

    companion object {
        fun create(root: Path, componentId: String, outputFiles: Map<String, ByteArray>): CompiledComponentArtifact {
            val directory = writableArtifactDirectory(root)
            val target = directory.resolve("$componentId-${UUID.randomUUID()}.jar")
            val temporary = directory.resolve(".${target.fileName}.tmp")
            try {
                JarOutputStream(Files.newOutputStream(temporary)).use { output ->
                    outputFiles.toSortedMap().forEach { (name, bytes) ->
                        output.putNextEntry(JarEntry(name))
                        output.write(bytes)
                        output.closeEntry()
                    }
                }
                moveReplacing(temporary, target)
                return CompiledComponentArtifact(
                    target,
                    outputFiles.keys.asSequence()
                        .filter { path -> path.endsWith(".class") }
                        .map { path -> path.removeSuffix(".class").replace('/', '.') }
                        .toSet(),
                    Sha256.file(target)
                )
            } finally {
                runCatching { Files.deleteIfExists(temporary) }
            }
        }

        fun copyFrom(
            root: Path,
            componentId: String,
            source: Path,
            expectedHash: String
        ): CompiledComponentArtifact {
            val directory = writableArtifactDirectory(root)
            val target = directory.resolve("$componentId-${UUID.randomUUID()}.jar")
            val temporary = directory.resolve(".${target.fileName}.tmp")
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
                check(Sha256.file(temporary) == expectedHash) { "Copied component JAR hash changed: $componentId" }
                moveReplacing(temporary, target)
                val ownedClasses = JarFile(target.toFile()).use { jar ->
                    jar.entries().asSequence()
                        .filterNot { entry -> entry.isDirectory }
                        .map { entry -> entry.name }
                        .filter { path -> path.endsWith(".class") }
                        .map { path -> path.removeSuffix(".class").replace('/', '.') }
                        .toSet()
                }
                return CompiledComponentArtifact(target, ownedClasses, expectedHash)
            } finally {
                runCatching { Files.deleteIfExists(temporary) }
            }
        }

        private fun moveReplacing(source: Path, target: Path) {
            runCatching {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun writableArtifactDirectory(preferred: Path): Path = runCatching {
            Files.createDirectories(preferred).also { directory ->
                check(Files.isWritable(directory)) { "Component artifact directory is not writable: $directory" }
            }
        }.getOrElse {
            val fallback = Path.of(System.getProperty("java.io.tmpdir"), "EternalScript-live-components")
            Files.createDirectories(fallback).also { directory ->
                check(Files.isWritable(directory)) { "Fallback component directory is not writable: $directory" }
            }
        }
    }
}
