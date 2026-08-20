package eternalscript.intellij.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import eternalscript.ide.protocol.IdeEnvironment
import eternalscript.ide.protocol.IdeEnvironmentCodec
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.intellij.model.EternalScriptEnvironmentProblem
import eternalscript.intellij.resolve.Idea262Facade
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal data class EternalScriptWorkspaceDescriptor(
    val id: String,
    val manifest: Path,
    val manifestDigest: String,
    val workspaceRoot: Path,
    val scriptRoot: Path,
    val environment: IdeEnvironment
) {
    fun contains(path: Path): Boolean = path.startsWith(scriptRoot)
}

internal data class WorkspaceRegistrySnapshot(
    val descriptors: List<EternalScriptWorkspaceDescriptor>,
    val problems: List<EternalScriptEnvironmentProblem>
)

internal class WorkspaceRegistry(
    private val project: Project,
    private val trusted: () -> Boolean = {
        ApplicationManager.getApplication().isUnitTestMode || Idea262Facade.isProjectTrusted(project)
    }
) {
    fun indexedManifestPaths(base: Path): List<Path> = FilenameIndex.getVirtualFilesByName(
        ENVIRONMENT_FILE_NAME,
        GlobalSearchScope.projectScope(project)
    ).asSequence()
        .mapIndexed { index, file ->
            if (index and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
            file
        }
        .filter(::isEnvironmentManifest)
        .mapNotNull(::safePath)
        .filter { path -> path.startsWith(base) }
        .distinct()
        .sorted()
        .toList()

    fun diskManifestPaths(base: Path): List<Path> {
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(base)) return emptyList()
        return Files.find(base, MAX_MANIFEST_DEPTH, { path, attributes ->
            ProgressManager.checkCanceled()
            attributes.isRegularFile && !attributes.isSymbolicLink && isEnvironmentManifest(path)
        }).use { paths ->
            paths.filter { path -> !containsExcludedSegment(base, path) }
                .sorted()
                .toList()
        }
    }

    fun load(manifests: Collection<Path>): WorkspaceRegistrySnapshot {
        val problems = mutableListOf<EternalScriptEnvironmentProblem>()
        if (!trusted()) {
            manifests.sorted().forEach { manifest ->
                problems += EternalScriptEnvironmentProblem.Untrusted(manifest)
            }
            return WorkspaceRegistrySnapshot(emptyList(), problems)
        }
        val descriptors = manifests.distinct().sorted().mapNotNull { manifest ->
            loadManifest(manifest, problems)
        }.sortedBy { descriptor -> descriptor.scriptRoot.toString() }
        return WorkspaceRegistrySnapshot(descriptors, problems)
    }

    fun nearest(descriptors: Collection<EternalScriptWorkspaceDescriptor>, path: Path): EternalScriptWorkspaceDescriptor? =
        descriptors.asSequence()
            .filter { descriptor -> descriptor.contains(path) }
            .maxByOrNull { descriptor -> descriptor.scriptRoot.nameCount }

    private fun loadManifest(
        manifest: Path,
        problems: MutableList<EternalScriptEnvironmentProblem>
    ): EternalScriptWorkspaceDescriptor? {
        val normalizedManifest = manifest.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalizedManifest, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalizedManifest)) {
            return null
        }
        val content = runCatching { Files.readAllBytes(normalizedManifest) }.getOrElse { error ->
            problems += EternalScriptEnvironmentProblem.Invalid(
                normalizedManifest,
                error.message ?: error.javaClass.name
            )
            return null
        }
        val actualVersion = runCatching { IdeEnvironmentCodec.peekProtocolVersion(content) }.getOrElse { error ->
            problems += EternalScriptEnvironmentProblem.Invalid(
                normalizedManifest,
                error.message ?: error.javaClass.name
            )
            return null
        }
        if (actualVersion != IdeProtocol.VERSION) {
            problems += EternalScriptEnvironmentProblem.Incompatible(
                normalizedManifest,
                actualVersion,
                IdeProtocol.VERSION
            )
            return null
        }
        val environment = runCatching { IdeEnvironmentCodec.decode(content) }.getOrElse { error ->
            problems += EternalScriptEnvironmentProblem.Invalid(
                normalizedManifest,
                error.message ?: error.javaClass.name
            )
            return null
        }
        if (!compatibleKotlin(environment.kotlinVersion())) {
            problems += EternalScriptEnvironmentProblem.IncompatibleKotlin(
                normalizedManifest,
                environment.kotlinVersion(),
                KotlinVersion.CURRENT.toString()
            )
            return null
        }
        val workspaceRoot = normalizedManifest.parent?.parent?.parent?.toAbsolutePath()?.normalize()
            ?: return null
        val scriptRoot = workspaceRoot.resolve(environment.scriptRoot().replace('/', java.io.File.separatorChar))
            .toAbsolutePath()
            .normalize()
        if (!scriptRoot.startsWith(workspaceRoot) || !safeRealDirectory(workspaceRoot, scriptRoot)) {
            problems += EternalScriptEnvironmentProblem.UnsafeScriptRoot(normalizedManifest, scriptRoot.toString())
            return null
        }
        val missingClasspath = environment.classpath().mapNotNull { uri ->
            runCatching { Path.of(uri).toAbsolutePath().normalize() }.getOrNull()
        }.filterNot { path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS) }
        if (missingClasspath.isNotEmpty()) {
            problems += EternalScriptEnvironmentProblem.MissingClasspath(normalizedManifest, missingClasspath)
            return null
        }
        val id = sha256(environment.environmentId() + "\u0000" + normalizedManifest.toUri().toASCIIString()).take(16)
        return EternalScriptWorkspaceDescriptor(
            id,
            normalizedManifest,
            IdeEnvironmentCodec.verifiedContentHash(content),
            workspaceRoot,
            scriptRoot,
            environment
        )
    }

    private fun safeRealDirectory(workspaceRoot: Path, scriptRoot: Path): Boolean {
        if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(workspaceRoot)) return false
        var current = workspaceRoot
        for (segment in workspaceRoot.relativize(scriptRoot)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return false
        }
        if (!Files.isDirectory(scriptRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(scriptRoot)) return false
        return runCatching { scriptRoot.toRealPath().startsWith(workspaceRoot.toRealPath()) }.getOrDefault(false)
    }

    private fun compatibleKotlin(runtimeVersion: String): Boolean {
        fun majorMinor(value: String): List<Int> = value.substringBefore('-').split('.')
            .take(2)
            .mapNotNull(String::toIntOrNull)
        return majorMinor(runtimeVersion) == SUPPORTED_KOTLIN_MAJOR_MINOR
    }

    private fun safePath(file: VirtualFile): Path? = runCatching {
        file.toNioPath().toAbsolutePath().normalize()
    }.getOrNull()

    private fun isEnvironmentManifest(file: VirtualFile): Boolean =
        file.name == ENVIRONMENT_FILE_NAME && file.parent?.name == "ide" && file.parent?.parent?.name == ".eternalscript"

    private fun isEnvironmentManifest(path: Path): Boolean =
        path.fileName.toString() == ENVIRONMENT_FILE_NAME && path.parent?.fileName?.toString() == "ide" &&
            path.parent?.parent?.fileName?.toString() == ".eternalscript"

    private fun containsExcludedSegment(base: Path, path: Path): Boolean = base.relativize(path).any { segment ->
        segment.toString() in EXCLUDED_DIRECTORIES
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }


    private companion object {
        const val ENVIRONMENT_FILE_NAME: String = "environment.properties"
        const val MAX_MANIFEST_DEPTH: Int = 24
        const val CANCELLATION_CHECK_MASK: Int = 0x3f
        val SUPPORTED_KOTLIN_MAJOR_MINOR: List<Int> = listOf(2, 4)
        val EXCLUDED_DIRECTORIES: Set<String> = setOf(".git", ".gradle", "build", "out", ".idea")
    }
}
