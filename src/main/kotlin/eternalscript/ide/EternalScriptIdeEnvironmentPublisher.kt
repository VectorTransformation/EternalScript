package eternalscript.ide

import eternalscript.messaging.MessageKey
import eternalscript.messaging.MessageLevel
import eternalscript.messaging.SystemMessage
import eternalscript.messaging.systemMessage
import eternalscript.ide.protocol.IdeEnvironment
import eternalscript.ide.protocol.IdeEnvironmentCodec
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.scripting.compilation.ScriptCompilationEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class EternalScriptIdeEnvironmentPublisher(
    workspace: File,
    private val system: (SystemMessage) -> Unit
) {
    private val workspace = workspace.toPath().toAbsolutePath().normalize()
    private val ideRoot = this.workspace.resolve(IdeProtocol.DIRECTORY).normalize()
    private val environmentFile = this.workspace.resolve(IdeProtocol.ENVIRONMENT_FILE).normalize()
    private var environmentId: String? = null

    init {
        require(ideRoot.startsWith(this.workspace) && environmentFile.startsWith(ideRoot))
    }

    @Synchronized
    fun publishEnvironmentIfChanged(environment: ScriptCompilationEnvironment) {
        runCatching {
            ensureSafeDirectory(ideRoot)
            val snapshot = IdeEnvironment(
                environmentId(),
                environment.fingerprint,
                "scripts",
                environment.classpath.map { file -> file.toPath().toAbsolutePath().normalize().toUri() }
            )
            val content = IdeEnvironmentCodec.encode(snapshot)
            val current = environmentFile.takeIf { path ->
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
            }?.let(Files::readAllBytes)
            if (current == null || !current.contentEquals(content)) atomicWrite(environmentFile, content)
        }.onFailure { error ->
            system(
                systemMessage(
                    MessageLevel.WARNING,
                    MessageKey.SYSTEM_IDE_ENVIRONMENT_PUBLISH_FAILED,
                    "error" to (error.message ?: error.javaClass.name),
                    cause = error
                )
            )
        }
    }

    private fun environmentId(): String = environmentId ?: run {
        val existing = environmentFile.takeIf { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
        }?.let { path ->
            runCatching { IdeEnvironmentCodec.decode(Files.readAllBytes(path)) }
                .getOrNull()
                ?.environmentId()
        }
        (existing ?: UUID.randomUUID().toString()).also { value -> environmentId = value }
    }

    private fun ensureSafeDirectory(directory: Path) {
        check(directory.startsWith(workspace)) { "IDE path is outside the plugin workspace: $directory" }
        var current = workspace
        check(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) {
            "Plugin workspace is not a real directory: $workspace"
        }
        workspace.relativize(directory).forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) {
                    "IDE path is not a real directory: $current"
                }
            } else {
                Files.createDirectory(current)
            }
        }
    }

    private fun atomicWrite(target: Path, content: ByteArray) {
        check(target.startsWith(ideRoot) && target != ideRoot)
        val temporary = target.parent.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        Files.write(temporary, content)
        try {
            runCatching {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

}
