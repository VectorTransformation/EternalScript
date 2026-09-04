package eternalscript.intellij.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import eternalscript.ide.protocol.IdeEnvironment
import eternalscript.ide.protocol.IdeEnvironmentCodec
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

    fun load(manifests: Collection<Path>): List<EternalScriptWorkspaceDescriptor> {
        if (!trusted()) return emptyList()
        return manifests.distinct().sorted().mapNotNull(::loadManifest)
            .sortedBy { descriptor -> descriptor.scriptRoot.toString() }
    }

    fun nearest(descriptors: Collection<EternalScriptWorkspaceDescriptor>, path: Path): EternalScriptWorkspaceDescriptor? =
        descriptors.asSequence()
            .filter { descriptor -> descriptor.contains(path) }
            .maxByOrNull { descriptor -> descriptor.scriptRoot.nameCount }

    private fun loadManifest(manifest: Path): EternalScriptWorkspaceDescriptor? {
        val normalizedManifest = manifest.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalizedManifest, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalizedManifest)) {
            return null
        }
        val content = runCatching { Files.readAllBytes(normalizedManifest) }.getOrNull() ?: return null
        val environment = runCatching { IdeEnvironmentCodec.decode(content) }.getOrNull() ?: return null
        val workspaceRoot = normalizedManifest.parent?.parent?.parent?.toAbsolutePath()?.normalize()
            ?: return null
        val scriptRoot = workspaceRoot.resolve(environment.scriptRoot().replace('/', java.io.File.separatorChar))
            .toAbsolutePath()
            .normalize()
        if (!scriptRoot.startsWith(workspaceRoot) || !safeRealDirectory(workspaceRoot, scriptRoot)) {
            return null
        }
        val missingClasspath = environment.classpath().mapNotNull { uri ->
            runCatching { Path.of(uri).toAbsolutePath().normalize() }.getOrNull()
        }.filterNot { path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS) }
        if (missingClasspath.isNotEmpty()) {
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
        val EXCLUDED_DIRECTORIES: Set<String> = setOf(".git", ".gradle", "build", "out", ".idea")
    }
}
