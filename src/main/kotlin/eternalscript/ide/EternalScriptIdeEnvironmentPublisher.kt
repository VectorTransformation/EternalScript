package eternalscript.ide

import eternalscript.feedback.FeedbackKey
import eternalscript.feedback.FeedbackLevel
import eternalscript.feedback.SystemFeedback
import eternalscript.feedback.systemFeedback
import eternalscript.ide.protocol.IdeEnvironment
import eternalscript.ide.protocol.IdeEnvironmentCodec
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.scripting.compilation.ScriptCompilationEnvironment
import eternalscript.util.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal data class EternalScriptIdeMigrationReport(
    val preservedPaths: List<String>,
    val failures: List<Pair<String, String>>
)

internal class EternalScriptIdeEnvironmentPublisher(
    workspace: File,
    private val pluginVersion: String,
    private val system: (SystemFeedback) -> Unit
) {
    private val workspace = workspace.toPath().toAbsolutePath().normalize()
    private val ideRoot = this.workspace.resolve(IdeProtocol.DIRECTORY).normalize()
    private val environmentFile = this.workspace.resolve(IdeProtocol.ENVIRONMENT_FILE).normalize()
    private var environmentId: String? = null

    init {
        require(ideRoot.startsWith(this.workspace) && environmentFile.startsWith(ideRoot))
    }

    fun prepare(): EternalScriptIdeMigrationReport {
        val preserved = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()
        ensureSafeDirectory(ideRoot)
        cleanupLegacyModels(failures)
        retireManagedWorkspace(preserved, failures)
        return EternalScriptIdeMigrationReport(preserved.sorted(), failures.sortedBy(Pair<String, String>::first))
    }

    @Synchronized
    fun publishEnvironmentIfChanged(environment: ScriptCompilationEnvironment) {
        runCatching {
            ensureSafeDirectory(ideRoot)
            val snapshot = IdeEnvironment(
                IdeProtocol.VERSION,
                environmentId(),
                pluginVersion,
                KotlinCompilerVersion.VERSION,
                environment.fingerprint,
                "scripts",
                environment.classpath.map { file -> file.toPath().toAbsolutePath().normalize().toUri() },
                environment.defaultImports
            )
            val content = IdeEnvironmentCodec.encode(snapshot)
            val current = environmentFile.takeIf { path ->
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
            }?.let(Files::readAllBytes)
            if (current == null || !current.contentEquals(content)) atomicWrite(environmentFile, content)
        }.onFailure { error ->
            system(
                systemFeedback(
                    FeedbackLevel.WARNING,
                    FeedbackKey.SYSTEM_IDE_ENVIRONMENT_PUBLISH_FAILED,
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
                ?.takeIf { environment -> environment.protocolVersion() == IdeProtocol.VERSION }
                ?.environmentId()
        }
        (existing ?: UUID.randomUUID().toString()).also { value -> environmentId = value }
    }

    private fun cleanupLegacyModels(failures: MutableList<Pair<String, String>>) {
        if (!Files.isDirectory(ideRoot, LinkOption.NOFOLLOW_LINKS)) return
        Files.list(ideRoot).use { children ->
            children.filter(::isLegacyModelTarget).forEach { target ->
                runCatching { deleteTree(target) }.onFailure { error ->
                    failures += relative(target) to (error.message ?: error.javaClass.name)
                }
            }
        }
    }

    private fun isLegacyModelTarget(path: Path): Boolean {
        val name = path.fileName.toString()
        return name == "models" ||
            name == "source-live" ||
            name == "current.json" ||
            name == "current-components.jar" ||
            name == "current-components.json" ||
            name == "bootstrap.classpath" ||
            name.startsWith(".completion-")
    }

    private fun retireManagedWorkspace(
        preserved: MutableList<String>,
        failures: MutableList<Pair<String, String>>
    ) {
        val manifest = workspace.resolve(".eternalscript/workspace-manifest.json")
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(manifest)) return
        val managed = runCatching {
            json.decodeFromString<LegacyWorkspaceManifest>(Files.readString(manifest)).managedHashes
        }.getOrElse { error ->
            failures += relative(manifest) to (error.message ?: error.javaClass.name)
            return
        }
        var hasPreserved = false
        legacyWorkspacePaths.forEach { path ->
            val expected = managed[path] ?: return@forEach
            val target = workspace.resolve(path).normalize()
            if (!target.startsWith(workspace) || target == workspace || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return@forEach
            }
            runCatching {
                val matches = isSafeDescendant(target) &&
                    Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(target) && Sha256.file(target) == expected
                if (matches) {
                    Files.deleteIfExists(target)
                } else {
                    hasPreserved = true
                    preserved += path
                }
            }.onFailure { error ->
                failures += path to (error.message ?: error.javaClass.name)
            }
        }
        removeIfEmpty(workspace.resolve("gradle/wrapper"))
        removeIfEmpty(workspace.resolve("gradle"))
        if (!hasPreserved && failures.none { (path) -> path in legacyWorkspacePaths }) {
            runCatching { Files.deleteIfExists(manifest) }.onFailure { error ->
                failures += relative(manifest) to (error.message ?: error.javaClass.name)
            }
        }
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

    private fun isSafeDescendant(target: Path): Boolean {
        if (!target.startsWith(workspace) || target == workspace) return false
        var current = workspace
        workspace.relativize(target).forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return false
        }
        return true
    }

    private fun deleteTree(target: Path) {
        check(target.startsWith(ideRoot) && target != ideRoot) { "Refusing to delete outside the IDE directory: $target" }
        if (Files.isSymbolicLink(target) || Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(target)
            return
        }
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
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

    private fun removeIfEmpty(directory: Path) {
        if (!isSafeDescendant(directory) ||
            !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(directory)
        ) return
        Files.list(directory).use { children ->
            if (!children.findAny().isPresent) Files.deleteIfExists(directory)
        }
    }

    private fun relative(path: Path): String = workspace.relativize(path).toString().replace('\\', '/')

    @Serializable
    private data class LegacyWorkspaceManifest(
        val schema: Int = 1,
        val managedHashes: Map<String, String> = emptyMap()
    )

    private companion object {
        val json: Json = Json { ignoreUnknownKeys = false }
        val legacyWorkspacePaths: Set<String> = setOf(
            "settings.gradle.kts",
            "build.gradle.kts",
            "gradle.properties",
            ".gitignore",
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties"
        )
    }
}
