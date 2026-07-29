package eternalScript.core.script.definition

import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ScriptRuntimeClasspathFingerprintTest {
    @Test
    fun `classpath snapshot includes path type size high resolution mtime and content`() {
        val base = ClasspathEntrySnapshot(
            classpathIndex = 0,
            normalizedPath = "C:/plugins/example.jar",
            type = ClasspathEntryType.REGULAR_FILE,
            size = 1024,
            lastModifiedSeconds = 100,
            lastModifiedNanos = 200,
            contentHash = "content-a"
        )
        val fingerprint = classpathSnapshot(listOf(base))

        assertEquals(fingerprint, classpathSnapshot(listOf(base)))
        assertNotEquals(fingerprint, classpathSnapshot(listOf(base.copy(normalizedPath = "C:/plugins/other.jar"))))
        assertNotEquals(fingerprint, classpathSnapshot(listOf(base.copy(type = ClasspathEntryType.OTHER))))
        assertNotEquals(fingerprint, classpathSnapshot(listOf(base.copy(size = 1025))))
        assertNotEquals(fingerprint, classpathSnapshot(listOf(base.copy(lastModifiedNanos = 201))))
        assertNotEquals(fingerprint, classpathSnapshot(listOf(base.copy(contentHash = "content-b"))))
    }

    @Test
    fun `classpath order affects the snapshot`() {
        val first = entry(index = 0, path = "C:/plugins/first.jar")
        val second = entry(index = 1, path = "C:/plugins/second.jar")

        assertNotEquals(
            classpathSnapshot(listOf(first, second)),
            classpathSnapshot(
                listOf(
                    second.copy(classpathIndex = 0),
                    first.copy(classpathIndex = 1)
                )
            )
        )
    }

    @Test
    fun `jar resource URLs resolve to their containing classpath file`() {
        val jar = Files.createTempFile("eternal-script-classpath", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.putNextEntry(JarEntry("kotlin/Unit.class"))
                output.write(byteArrayOf(0))
                output.closeEntry()
            }
            val resource = java.net.URI.create(
                "jar:${jar.toUri()}!/kotlin/Unit.class"
            ).toURL()

            assertEquals(
                jar.toAbsolutePath().normalize().toFile(),
                resource.toClasspathFile()?.toPath()?.toAbsolutePath()?.normalize()?.toFile()
            )
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    private fun entry(index: Int, path: String) = ClasspathEntrySnapshot(
        classpathIndex = index,
        normalizedPath = path,
        type = ClasspathEntryType.REGULAR_FILE,
        size = 1024,
        lastModifiedSeconds = 100,
        lastModifiedNanos = 200,
        contentHash = "content"
    )
}
