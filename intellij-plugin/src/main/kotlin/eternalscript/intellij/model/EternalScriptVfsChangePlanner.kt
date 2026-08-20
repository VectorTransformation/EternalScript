package eternalscript.intellij.model

import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import eternalscript.ide.protocol.IdeProtocol
import java.nio.file.Path

internal data class EternalScriptVfsChangePlan(
    val rediscover: Boolean,
    val changedPaths: Set<Path>
)

internal object EternalScriptVfsChangePlanner {
    fun plan(events: List<VFileEvent>): EternalScriptVfsChangePlan {
        if (events.any { event -> isManifestPath(event.path) || isStructuralChange(event) }) {
            return EternalScriptVfsChangePlan(rediscover = true, changedPaths = emptySet())
        }
        val paths = events.asSequence()
            .filter { event -> event.path.endsWith(EternalScriptProjectService.ETERNAL_SCRIPT_SUFFIX) }
            .mapNotNull { event -> normalize(event.path) }
            .toCollection(linkedSetOf())
        return EternalScriptVfsChangePlan(rediscover = false, changedPaths = paths)
    }

    private fun isStructuralChange(event: VFileEvent): Boolean = when (event) {
        is VFileMoveEvent,
        is VFileCopyEvent -> true
        is VFilePropertyChangeEvent -> event.isRename
        is VFileCreateEvent -> event.isDirectory
        is VFileDeleteEvent -> event.file.isDirectory
        else -> false
    }

    private fun isManifestPath(path: String): Boolean =
        path.replace('\\', '/').endsWith(IdeProtocol.ENVIRONMENT_FILE)

    private fun normalize(path: String): Path? = runCatching {
        Path.of(path).toAbsolutePath().normalize()
    }.getOrNull()
}
