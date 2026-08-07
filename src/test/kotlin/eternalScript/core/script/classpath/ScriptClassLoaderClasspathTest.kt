package eternalScript.core.script.classpath

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptClassLoaderClasspathTest {
    @Test
    fun `embedded URL class loader files are discovered without Paper internals`() {
        val root = Path.of("codex", "temp", "script-classloader-classpath-tests")
            .toAbsolutePath()
            .normalize()
        Files.createDirectories(root)
        val library = Files.createTempFile(root, "embedded-library-", ".jar")

        try {
            URLClassLoader(arrayOf(library.toUri().toURL()), null).use { delegate ->
                val loader = EmbeddedDelegateClassLoader(delegate)

                assertEquals(listOf(loader, delegate), loader.ownedClassLoaders())
                assertEquals(
                    listOf(library.toAbsolutePath().normalize().toFile()),
                    loader.embeddedClasspathFiles()
                )
            }
        } finally {
            Files.deleteIfExists(library)
        }
    }

    private class EmbeddedDelegateClassLoader(
        @Suppress("unused")
        private val libraryLoader: ClassLoader
    ) : ClassLoader(null)
}
