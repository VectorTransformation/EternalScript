package eternalscript.intellij.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import eternalscript.intellij.model.EternalScriptIncrementalPlanner
import eternalscript.intellij.workspace.EternalScriptWorkspaceDescriptor
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class IndexedScriptFile(
    val path: Path,
    val url: String,
    val active: Boolean
)

internal class ScriptFileIndex {
    private val files = ConcurrentHashMap<String, Map<String, IndexedScriptFile>>()
    private val scanCounter = AtomicLong()

    fun enumerate(descriptor: EternalScriptWorkspaceDescriptor): Map<String, IndexedScriptFile> {
        scanCounter.incrementAndGet()
        val discovered = linkedMapOf<String, IndexedScriptFile>()
        if (!Files.isDirectory(descriptor.scriptRoot, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(descriptor.scriptRoot)
        ) return emptyMap()
        Files.walkFileTree(descriptor.scriptRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                ProgressManager.checkCanceled()
                if (Files.isSymbolicLink(directory) || attributes.isOther) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                ProgressManager.checkCanceled()
                if (attributes.isRegularFile && !attributes.isSymbolicLink && isScript(file)) {
                    val normalized = file.toAbsolutePath().normalize()
                    val url = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(normalized)?.url
                        ?: VfsUtilCore.pathToUrl(normalized.toString())
                    discovered[url] = IndexedScriptFile(
                        normalized,
                        url,
                        EternalScriptIncrementalPlanner.isActivePath(descriptor.scriptRoot, normalized)
                    )
                }
                return FileVisitResult.CONTINUE
            }
        })
        return discovered.toSortedMap()
    }

    fun replace(workspaceId: String, discovered: Map<String, IndexedScriptFile>) {
        files[workspaceId] = discovered.toMap()
    }

    fun update(descriptor: EternalScriptWorkspaceDescriptor, path: Path): IndexedScriptFile? {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(descriptor.scriptRoot) || !isScript(normalized)) return null
        val mutable = files[descriptor.id].orEmpty().toMutableMap()
        val url = LocalFileSystem.getInstance().findFileByNioFile(normalized)?.url
            ?: VfsUtilCore.pathToUrl(normalized.toString())
        val entry = if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(normalized) &&
            isSafeDescendant(descriptor.scriptRoot, normalized)
        ) {
            IndexedScriptFile(
                normalized,
                url,
                EternalScriptIncrementalPlanner.isActivePath(descriptor.scriptRoot, normalized)
            ).also { value -> mutable[url] = value }
        } else {
            mutable.remove(url)
            null
        }
        files[descriptor.id] = mutable.toMap()
        return entry
    }

    fun files(workspaceId: String): Map<String, IndexedScriptFile> = files[workspaceId].orEmpty()

    fun removeMissingWorkspaces(validIds: Set<String>) {
        files.keys.removeIf { id -> id !in validIds }
    }

    fun scanCount(): Long = scanCounter.get()

    private fun isSafeDescendant(root: Path, path: Path): Boolean {
        var current = root
        for (segment in root.relativize(path)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return false
        }
        return runCatching { path.toRealPath().startsWith(root.toRealPath()) }.getOrDefault(false)
    }

    private fun isScript(path: Path): Boolean = path.fileName.toString().endsWith(ETERNAL_SCRIPT_SUFFIX)

    private companion object {
        const val ETERNAL_SCRIPT_SUFFIX: String = ".eternal.kts"
    }
}
