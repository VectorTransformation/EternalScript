package eternalScript.core.script.definition

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptCompilationCacheRetentionTest {
    @Test
    fun `cache artifact remains retained until every generation releases it`() {
        val root = Files.createTempDirectory("eternal-script-cache-retention-")
        try {
            root.resolve(".schema").writeText("9")
            root.resolve(".generation").writeText("existing-generation")
            val artifact = root.resolve("nested/project.jar")
            Files.createDirectories(artifact.parent)
            artifact.writeText("artifact")
            Files.setLastModifiedTime(artifact, FileTime.fromMillis(0))

            val cache = ScriptCompilationCacheStorage(root.toFile())
            val normalizedArtifact = artifact.parent.resolve(".").resolve("project.jar").toFile()
            cache.retain(artifact.toFile())
            cache.retain(normalizedArtifact)
            cache.release(artifact.toFile())
            val retainedSnapshot = cache.retainedGenerationJars()
            assertEquals(setOf(artifact.toAbsolutePath().normalize()), retainedSnapshot)

            cache.prepare()
            assertTrue(Files.exists(artifact), "age pruning must preserve the remaining reference")

            cache.reset()
            assertTrue(Files.exists(artifact), "cache reset must preserve the remaining reference")

            cache.release(normalizedArtifact)
            assertTrue(cache.retainedGenerationJars().isEmpty())
            assertEquals(
                setOf(artifact.toAbsolutePath().normalize()),
                retainedSnapshot,
                "callers must receive a stable copy of the retained paths"
            )
            cache.reset()
            assertFalse(Files.exists(artifact), "the artifact may be removed after the final release")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
