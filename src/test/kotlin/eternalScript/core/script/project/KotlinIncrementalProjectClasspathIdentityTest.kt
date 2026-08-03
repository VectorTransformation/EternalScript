package eternalScript.core.script.project

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.script.experimental.jvm.util.classpathFromClassloader
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
class KotlinIncrementalProjectClasspathIdentityTest {
    @Test
    fun `plugin roster fingerprint participates in generation artifact cache key`() {
        val temporaryRoot = Path.of("build", "tmp", "classpath-identity-tests")
            .toAbsolutePath()
            .normalize()
        Files.createDirectories(temporaryRoot)
        val cacheRoot = Files.createTempDirectory(temporaryRoot, "compiler-")
        try {
            val classpath = classpathFromClassloader(javaClass.classLoader)
                .orEmpty()
                .map(File::toPath)
                .filter(Files::exists)
                .distinct()
            val module = ScriptProjectSource.compose(
                listOf(
                    ScriptProjectFile(
                        "identity.kt",
                        """
                        package cacheidentity

                        internal fun identity(): String = "stable"
                        """.trimIndent()
                    )
                )
            ).module
            val first = compiler(cacheRoot, classpath, "plugin-a:1").compile(module)
            val changedRoster = compiler(cacheRoot, classpath, "plugin-a:2").compile(module)
            val cachedRoster = compiler(cacheRoot, classpath, "plugin-a:2").compile(module)

            assertTrue(first.isSuccess)
            assertTrue(changedRoster.isSuccess)
            assertFalse(changedRoster.cacheHit)
            assertNotEquals(first.generationJar, changedRoster.generationJar)
            assertTrue(cachedRoster.cacheHit)
        } finally {
            check(cacheRoot.startsWith(temporaryRoot))
            cacheRoot.toFile().deleteRecursively()
        }
    }

    private fun compiler(
        cacheRoot: Path,
        classpath: List<Path>,
        identity: String
    ) = KotlinIncrementalProjectCompiler(
        cacheRoot = cacheRoot,
        classpath = classpath,
        classpathIdentity = identity,
        implementationClassLoader = javaClass.classLoader
    )
}
