package eternalscript.ide

import eternalscript.ide.protocol.IdeEnvironmentCodec
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.scripting.compilation.RuntimeDependencies
import eternalscript.scripting.compilation.RuntimeDependencyClassLoader
import eternalscript.scripting.compilation.ScriptCompilationEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EternalScriptIdeEnvironmentPublisherTest {
    @Test
    fun `publishes an environment-only protocol atomically`() {
        val workspace = createTempDirectory("eternalscript-ide-environment")
        val message = mutableListOf<eternalscript.messaging.SystemMessage>()
        val publisher = EternalScriptIdeEnvironmentPublisher(workspace.toFile(), message::add)
        val environment = environment(workspace)
        try {
            publisher.publishEnvironmentIfChanged(environment)

            val target = workspace.resolve(IdeProtocol.ENVIRONMENT_FILE)
            assertTrue(Files.isRegularFile(target))
            val published = IdeEnvironmentCodec.decode(Files.readAllBytes(target))
            assertEquals("fingerprint", published.environmentFingerprint())
            assertEquals("scripts", published.scriptRoot())
            assertTrue(published.environmentId().isNotBlank())
            assertTrue(message.isEmpty())

            val firstId = published.environmentId()
            val secondPublisher = EternalScriptIdeEnvironmentPublisher(workspace.toFile(), message::add)
            secondPublisher.publishEnvironmentIfChanged(environment)
            val republished = IdeEnvironmentCodec.decode(Files.readAllBytes(target))
            assertEquals(firstId, republished.environmentId())
        } finally {
            environment.close()
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
}
