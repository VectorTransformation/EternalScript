package eternalScript.core.script.project

import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.script.experimental.jvm.util.classpathFromClassloader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
class KotlinCompilerComponentsTest {
    @Test
    fun `extracted compiler components preserve the successful pipeline`() {
        val root = Files.createTempDirectory("eternal-script-compiler-components")
        try {
            val markerClasspath = root.resolve("marker-classpath")
            Files.createDirectories(markerClasspath)
            Files.writeString(markerClasspath.resolve("marker.txt"), "one")
            val classpath = (
                classpathFromClassloader(javaClass.classLoader)
                    .orEmpty()
                    .map(File::toPath) + listOf(markerClasspath)
            ).filter(Files::exists).distinct()
            val workspace = KotlinCompilerWorkspace(root.resolve("cache"))
            val snapshots = KotlinClasspathSnapshotStore(
                classpath = classpath,
                classpathIdentity = "component-test",
                snapshotsDirectory = workspace.classpathSnapshotsDirectory
            )
            val buildTools = KotlinBuildToolsCompiler(javaClass.classLoader, snapshots.classpath)
            val artifacts = GenerationArtifactStore(
                artifactsDirectory = workspace.artifactsDirectory,
                classesDirectory = workspace.classesDirectory,
                compilerVersion = buildTools.compilerVersion
            )
            val module = componentTestModule()

            workspace.prepare()
            val changes = workspace.syncSources(module)
            val classpathState = snapshots.capture()
            val result = buildTools.compile(
                workspace = workspace,
                sourceChanges = changes,
                classpathState = classpathState,
                snapshotStore = snapshots,
                renderer = SilentCompilerMessageRenderer
            )

            assertEquals(CompilationResult.COMPILATION_SUCCESS, result)
            snapshots.requireUnchanged(classpathState)
            workspace.persistCompiledSourceState(changes.currentState)
            val artifact = artifacts.artifact(module, classpathState.fingerprint)
            assertEquals(artifact, artifacts.packageGeneration(artifact, module))
            assertTrue(artifacts.isUsable(artifact, module))

            val retainedCleanup = artifacts.prune(
                retained = setOf(artifact.toAbsolutePath().normalize()),
                maxFiles = 0,
                cutoff = Instant.MAX
            )
            assertTrue(retainedCleanup.removed.isEmpty())
            assertTrue(Files.exists(artifact))

            Files.writeString(markerClasspath.resolve("marker.txt"), "changed-marker-two")
            assertNotEquals(classpathState.fingerprint, snapshots.capture().fingerprint)
            assertFailsWith<IllegalStateException> {
                snapshots.requireUnchanged(classpathState)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun componentTestModule(): ScriptProjectModule =
        ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "component.kt",
                    """
                    package probe.component

                    internal fun componentValue(): String = "ready"
                    """.trimIndent()
                )
            )
        ).module

    private object SilentCompilerMessageRenderer : CompilerMessageRenderer {
        override fun render(
            severity: CompilerMessageRenderer.Severity,
            message: String,
            location: CompilerMessageRenderer.SourceLocation?
        ): String = ""
    }
}
