package eternalscript.ide

import eternalscript.ide.protocol.IdeEnvironmentCodec
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.scripting.compilation.RuntimeDependencies
import eternalscript.scripting.compilation.RuntimeDependencyClassLoader
import eternalscript.scripting.compilation.ScriptCompilationEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EternalScriptIdeEnvironmentPublisherTest {
    @Test
    fun `publishes an environment-only protocol atomically`() {
        val workspace = createTempDirectory("eternalscript-ide-environment")
        val feedback = mutableListOf<eternalscript.feedback.SystemFeedback>()
        val publisher = EternalScriptIdeEnvironmentPublisher(workspace.toFile(), "2.1.0-test", feedback::add)
        val environment = environment(workspace)
        try {
            publisher.prepare()
            publisher.publishEnvironmentIfChanged(environment)

            val target = workspace.resolve(IdeProtocol.ENVIRONMENT_FILE)
            assertTrue(Files.isRegularFile(target))
            val published = IdeEnvironmentCodec.decode(Files.readAllBytes(target))
            assertEquals(IdeProtocol.VERSION, published.protocolVersion())
            assertEquals("2.1.0-test", published.runtimePluginVersion())
            assertEquals("fingerprint", published.environmentFingerprint())
            assertEquals("scripts", published.scriptRoot())
            assertTrue(published.environmentId().isNotBlank())
            assertEquals(listOf("org.bukkit.Bukkit", "example.Type as Alias"), published.defaultImports())
            assertTrue(feedback.isEmpty())

            val firstId = published.environmentId()
            val secondPublisher = EternalScriptIdeEnvironmentPublisher(workspace.toFile(), "2.1.0-test", feedback::add)
            secondPublisher.publishEnvironmentIfChanged(environment)
            val republished = IdeEnvironmentCodec.decode(Files.readAllBytes(target))
            assertEquals(firstId, republished.environmentId())
        } finally {
            environment.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `retires only recognized generated and unchanged managed files`() {
        val workspace = createTempDirectory("eternalscript-ide-migration")
        try {
            val ide = workspace.resolve(".eternalscript/ide").createDirectories()
            ide.resolve("models/model/model.json").apply {
                parent.createDirectories()
                writeText("generated")
            }
            ide.resolve("current-components.jar").writeText("generated")
            val unchanged = workspace.resolve("settings.gradle.kts").apply { writeText("managed") }
            val modified = workspace.resolve("gradle.properties").apply { writeText("user change") }
            workspace.resolve("scripts/keep.eternal.kts").apply {
                parent.createDirectories()
                writeText("onLoad { }")
            }
            workspace.resolve(".eternalscript/workspace-manifest.json").writeText(
                """{"schema":1,"managedHashes":{"settings.gradle.kts":"${sha256(unchanged)}","gradle.properties":"${sha256("managed".toByteArray())}"}}"""
            )

            val report = EternalScriptIdeEnvironmentPublisher(workspace.toFile(), "2.1.0", {}).prepare()

            assertFalse(Files.exists(ide.resolve("models")))
            assertFalse(Files.exists(ide.resolve("current-components.jar")))
            assertFalse(Files.exists(unchanged))
            assertTrue(Files.exists(modified))
            assertTrue(Files.exists(workspace.resolve("scripts/keep.eternal.kts")))
            assertEquals(listOf("gradle.properties"), report.preservedPaths)
            assertTrue(report.failures.isEmpty())
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun environment(workspace: Path): ScriptCompilationEnvironment {
        val runtimeLoader = RuntimeDependencyClassLoader(emptyArray(), javaClass.classLoader, emptyList())
        return ScriptCompilationEnvironment(
            ScriptCompilationConfiguration(),
            javaClass.classLoader,
            runtimeLoader,
            listOf(workspace.resolve("plugin.jar").toFile()),
            listOf("org.bukkit.Bukkit", "example.Type as Alias"),
            "fingerprint",
            RuntimeDependencies(runtimeLoader)
        )
    }

    private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
