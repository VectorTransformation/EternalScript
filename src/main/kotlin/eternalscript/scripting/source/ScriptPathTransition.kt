package eternalscript.scripting.source

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal sealed interface ScriptTargetPreparation {
    data class Ready(val transition: ScriptPathTransition) : ScriptTargetPreparation
    data class Invalid(val reason: String) : ScriptTargetPreparation
    data class NotFound(val path: String) : ScriptTargetPreparation
}

internal class ScriptPathTransition internal constructor(
    val target: ScriptTarget,
    private val sourcePath: Path?,
    private val destinationPath: Path?
) {
    private enum class State {
        PLANNED,
        APPLIED,
        FINALIZED
    }

    private var state = State.PLANNED

    val changed: Boolean
        get() = sourcePath != null

    @Synchronized
    fun apply() {
        when (state) {
            State.APPLIED -> return
            State.FINALIZED -> error("Script path transition has already been finalized: ${target.path}")
            State.PLANNED -> Unit
        }
        if (sourcePath != null && destinationPath != null) {
            moveWithoutReplace(sourcePath, destinationPath)
        }
        state = State.APPLIED
    }

    @Synchronized
    fun commit() {
        check(!changed || state == State.APPLIED) {
            "Script path transition was committed before it was applied: ${target.path}"
        }
        state = State.FINALIZED
    }

    @Synchronized
    fun rollback() {
        if (state == State.FINALIZED) return
        if (state == State.APPLIED && sourcePath != null && destinationPath != null) {
            moveWithoutReplace(destinationPath, sourcePath)
        }
        state = State.FINALIZED
    }
}

internal fun prepareScriptLoadTarget(root: File, path: String): ScriptTargetPreparation =
    prepareScriptTarget(root, path, load = true)

internal fun prepareScriptUnloadTarget(root: File, path: String): ScriptTargetPreparation =
    prepareScriptTarget(root, path, load = false)

private fun prepareScriptTarget(root: File, path: String, load: Boolean): ScriptTargetPreparation {
    val canonical = when (val validation = validateScriptTargetPath(path)) {
        is ScriptPathResult.Valid -> validation.path
        is ScriptPathResult.Invalid -> return ScriptTargetPreparation.Invalid(validation.reason)
    }
    val rootPath = root.toPath().toAbsolutePath().normalize()
    if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(rootPath)) {
        return ScriptTargetPreparation.Invalid("Scripts path is not a normal directory: $rootPath")
    }
    val enabledPath = rootPath.resolve(canonical).normalize()
    if (!enabledPath.startsWith(rootPath) || enabledPath == rootPath) {
        return ScriptTargetPreparation.Invalid("Script target is outside the scripts directory")
    }
    if (containsSymbolicLink(rootPath, enabledPath.parent)) {
        return ScriptTargetPreparation.Invalid("Script target path must not contain symbolic links")
    }
    val disabledPath = enabledPath.resolveSibling("-${enabledPath.fileName}")
    val enabledExists = Files.exists(enabledPath, LinkOption.NOFOLLOW_LINKS)
    val disabledExists = Files.exists(disabledPath, LinkOption.NOFOLLOW_LINKS)
    if (enabledExists && disabledExists) {
        return ScriptTargetPreparation.Invalid(
            "Both enabled and disabled targets exist: $canonical and ${logicalScriptPath(disabledPath, rootPath)}"
        )
    }

    val existing = when {
        enabledExists -> enabledPath
        disabledExists -> disabledPath
        else -> return ScriptTargetPreparation.NotFound(canonical)
    }
    val target = inspectTarget(existing, canonical)
        ?: return ScriptTargetPreparation.Invalid(
            "Script target must be a normal directory or a .$ETERNAL_SCRIPT_EXTENSION file: $canonical"
        )
    val from = when {
        load && disabledExists -> disabledPath
        !load && enabledExists -> enabledPath
        else -> null
    }
    val to = when {
        load && disabledExists -> enabledPath
        !load && enabledExists -> disabledPath
        else -> null
    }
    return ScriptTargetPreparation.Ready(
        ScriptPathTransition(target, sourcePath = from, destinationPath = to)
    )
}

private fun inspectTarget(path: Path, canonical: String): ScriptTarget? {
    if (Files.isSymbolicLink(path)) return null
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        return ScriptTarget(canonical, ScriptTargetKind.DIRECTORY)
    }
    if (
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
        canonical.endsWith(".$ETERNAL_SCRIPT_EXTENSION")
    ) {
        return ScriptTarget(canonical, ScriptTargetKind.FILE)
    }
    return null
}

private fun containsSymbolicLink(root: Path, target: Path): Boolean {
    var current = root
    root.relativize(target).forEach { part ->
        current = current.resolve(part)
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) return true
    }
    return false
}

private fun moveWithoutReplace(source: Path, target: Path) {
    check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Script target already exists: $target" }
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}
