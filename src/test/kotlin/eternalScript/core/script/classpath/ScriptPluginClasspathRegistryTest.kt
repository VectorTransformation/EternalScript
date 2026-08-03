package eternalScript.core.script.classpath

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScriptPluginClasspathRegistryTest {
    @AfterTest
    fun clearRegistry() {
        ScriptPluginClasspathRegistry.resetForTests()
    }

    @Test
    fun `classpath consumers fail clearly before ServerLoad refresh`() {
        ScriptPluginClasspathRegistry.resetForTests()

        val failure = assertFailsWith<IllegalStateException> {
            ScriptPluginClasspathRegistry.requireCurrent()
        }

        assertTrue(failure.message.orEmpty().contains("after ServerLoadEvent"))
    }

    @Test
    fun `snapshot order and fingerprint are deterministic by plugin name`() {
        val files = testFiles()
        try {
            val parent = javaClass.classLoader
            val alphaLoader = PassthroughClassLoader(parent)
            val betaLoader = PassthroughClassLoader(parent)
            val alpha = CapturedScriptPlugin("Alpha", "1.0.0", alphaLoader, listOf(files.alpha))
            val beta = CapturedScriptPlugin("beta", "2.0.0", betaLoader, listOf(files.beta))
            val first = ScriptPluginClasspathRegistry.buildSnapshot(
                capture(1, parent, listOf(beta, alpha), files.core),
                listOf(files.library)
            )
            val second = ScriptPluginClasspathRegistry.buildSnapshot(
                capture(2, parent, listOf(alpha, beta), files.core),
                listOf(files.library)
            )

            assertEquals(listOf("Alpha", "beta"), first.plugins.map { plugin -> plugin.name })
            assertEquals(
                listOf(files.core, files.alpha, files.beta, files.library).map { file ->
                    file.toPath().toAbsolutePath().normalize().toFile()
                },
                first.files
            )
            assertEquals(first.fingerprint, second.fingerprint)
            assertEquals(first.files, second.files)
        } finally {
            files.delete()
        }
    }

    @Test
    fun `core and each plugin preserve loader classpath order`() {
        val files = testFiles()
        try {
            val parent = javaClass.classLoader
            val loader = PassthroughClassLoader(parent)
            val snapshot = ScriptPluginClasspathRegistry.buildSnapshot(
                ScriptPluginClasspathCapture(
                    revision = 1,
                    parentClassLoader = parent,
                    coreFiles = listOf(files.beta, files.core),
                    plugins = listOf(
                        CapturedScriptPlugin(
                            "Alpha",
                            "1.0.0",
                            loader,
                            listOf(files.beta, files.alpha)
                        )
                    )
                ),
                listOf(files.library)
            )

            assertEquals(
                listOf(files.beta, files.core).map { file ->
                    file.toPath().toAbsolutePath().normalize().toFile()
                },
                snapshot.coreFiles
            )
            assertEquals(
                listOf(files.beta, files.alpha).map { file ->
                    file.toPath().toAbsolutePath().normalize().toFile()
                },
                snapshot.plugins.single().files
            )
            assertEquals(
                listOf(files.beta, files.core, files.alpha, files.library).map { file ->
                    file.toPath().toAbsolutePath().normalize().toFile()
                },
                snapshot.files
            )
        } finally {
            files.delete()
        }
    }

    @Test
    fun `plugin roster and jar content participate in fingerprint`() {
        val files = testFiles()
        try {
            val parent = javaClass.classLoader
            val loader = PassthroughClassLoader(parent)
            val base = ScriptPluginClasspathRegistry.buildSnapshot(
                capture(
                    1,
                    parent,
                    listOf(CapturedScriptPlugin("Alpha", "1.0.0", loader, listOf(files.alpha))),
                    files.core
                ),
                listOf(files.library)
            )
            val changedVersion = ScriptPluginClasspathRegistry.buildSnapshot(
                capture(
                    2,
                    parent,
                    listOf(CapturedScriptPlugin("Alpha", "1.0.1", loader, listOf(files.alpha))),
                    files.core
                ),
                listOf(files.library)
            )
            files.alpha.toPath().writeText("changed-content-with-another-size")
            val changedContent = ScriptPluginClasspathRegistry.buildSnapshot(
                capture(
                    3,
                    parent,
                    listOf(CapturedScriptPlugin("Alpha", "1.0.0", loader, listOf(files.alpha))),
                    files.core
                ),
                listOf(files.library)
            )

            assertNotEquals(base.fingerprint, changedVersion.fingerprint)
            assertNotEquals(base.fingerprint, changedContent.fingerprint)
        } finally {
            files.delete()
        }
    }

    @Test
    fun `older asynchronous refresh cannot replace newer plugin roster`() {
        val files = testFiles()
        try {
            val parent = javaClass.classLoader
            val loader = PassthroughClassLoader(parent)
            val oldCapture = capture(
                10,
                parent,
                listOf(CapturedScriptPlugin("Alpha", "1.0.0", loader, listOf(files.alpha))),
                files.core
            )
            val newCapture = capture(
                11,
                parent,
                listOf(CapturedScriptPlugin("Alpha", "2.0.0", loader, listOf(files.alpha))),
                files.core
            )

            val current = ScriptPluginClasspathRegistry.refresh(newCapture, listOf(files.library))
            val staleResult = ScriptPluginClasspathRegistry.refresh(oldCapture, listOf(files.library))

            assertSame(current, staleResult)
            assertSame(current, ScriptPluginClasspathRegistry.current())
        } finally {
            files.delete()
        }
    }

    @Test
    fun `clear prevents an in-flight stale capture from republishing plugin loaders`() {
        val files = testFiles()
        try {
            val parent = javaClass.classLoader
            val staleCapture = capture(
                0,
                parent,
                listOf(
                    CapturedScriptPlugin(
                        "Alpha",
                        "1.0.0",
                        PassthroughClassLoader(parent),
                        listOf(files.alpha)
                    )
                ),
                files.core
            )

            ScriptPluginClasspathRegistry.clear()
            assertFailsWith<CancellationException> {
                ScriptPluginClasspathRegistry.refresh(staleCapture, listOf(files.library))
            }

            assertNull(ScriptPluginClasspathRegistry.current())
        } finally {
            files.delete()
        }
    }

    private fun capture(
        revision: Long,
        parent: ClassLoader,
        plugins: List<CapturedScriptPlugin>,
        core: java.io.File
    ) = ScriptPluginClasspathCapture(
        revision = revision,
        parentClassLoader = parent,
        coreFiles = listOf(core),
        plugins = plugins
    )

    private fun testFiles(): TestFiles {
        val directory = Path.of("build", "tmp", "script-classpath-registry-tests")
        Files.createDirectories(directory)
        return TestFiles(
            core = Files.createTempFile(directory, "core-", ".jar").apply {
                writeText("core")
            }.toFile(),
            library = Files.createTempFile(directory, "library-", ".jar").apply {
                writeText("library")
            }.toFile(),
            alpha = Files.createTempFile(directory, "alpha-", ".jar").apply {
                writeText("alpha")
            }.toFile(),
            beta = Files.createTempFile(directory, "beta-", ".jar").apply {
                writeText("beta")
            }.toFile()
        )
    }

    private data class TestFiles(
        val core: java.io.File,
        val library: java.io.File,
        val alpha: java.io.File,
        val beta: java.io.File
    ) {
        fun delete() {
            listOf(core, library, alpha, beta).forEach { file ->
                Files.deleteIfExists(file.toPath())
            }
        }
    }

    private class PassthroughClassLoader(
        parent: ClassLoader
    ) : ClassLoader(parent)
}
