package eternalscript.scripting.source

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

internal const val ETERNAL_SCRIPT_EXTENSION: String = "eternal.kts"

internal sealed interface ScriptPathResult {
    data class Valid(val path: String) : ScriptPathResult
    data class Invalid(val reason: String) : ScriptPathResult
}

internal enum class ScriptTargetKind {
    FILE,
    DIRECTORY
}

internal data class ScriptTarget(
    val path: String,
    val kind: ScriptTargetKind
) {
    fun contains(sourcePath: String): Boolean = when (kind) {
        ScriptTargetKind.FILE -> sourcePath == path
        ScriptTargetKind.DIRECTORY -> sourcePath.startsWith("$path/")
    }
}

internal fun validateScriptPath(path: String): ScriptPathResult {
    val candidate = path.trim()
    if (candidate.isEmpty()) return ScriptPathResult.Invalid("Script path must not be blank")
    if ('\\' in candidate) return ScriptPathResult.Invalid("Script paths must use forward slashes")
    if (!candidate.endsWith(".$ETERNAL_SCRIPT_EXTENSION")) {
        return ScriptPathResult.Invalid("Script path must end with .$ETERNAL_SCRIPT_EXTENSION")
    }
    if (candidate.startsWith('/') || DRIVE_PREFIX.matches(candidate)) {
        return ScriptPathResult.Invalid("Absolute script paths are not allowed")
    }

    val parsed = try {
        Path.of(candidate)
    } catch (_: InvalidPathException) {
        return ScriptPathResult.Invalid("Script path is invalid")
    }
    if (parsed.isAbsolute || parsed.any { part -> part.toString() == "." || part.toString() == ".." }) {
        return ScriptPathResult.Invalid("Script path traversal is not allowed")
    }
    val normalized = parsed.normalize().invariantSeparatorsPathString
    if (normalized != candidate || normalized.startsWith("../")) {
        return ScriptPathResult.Invalid("Script path must be normalized")
    }
    return ScriptPathResult.Valid(normalized)
}

internal fun validateScriptTargetPath(path: String): ScriptPathResult {
    val candidate = path.trim()
    if (candidate.isEmpty()) return ScriptPathResult.Invalid("Script target path must not be blank")
    if ('\\' in candidate) return ScriptPathResult.Invalid("Script target paths must use forward slashes")
    if (candidate.startsWith('/') || DRIVE_PREFIX.matches(candidate)) {
        return ScriptPathResult.Invalid("Absolute script target paths are not allowed")
    }

    val parsed = try {
        Path.of(candidate)
    } catch (_: InvalidPathException) {
        return ScriptPathResult.Invalid("Script target path is invalid")
    }
    if (parsed.isAbsolute || parsed.any { part -> part.toString() == "." || part.toString() == ".." }) {
        return ScriptPathResult.Invalid("Script target path traversal is not allowed")
    }
    val normalized = parsed.normalize().invariantSeparatorsPathString
    if (normalized != candidate || normalized.startsWith("../")) {
        return ScriptPathResult.Invalid("Script target path must be normalized")
    }

    val parts = parsed.map(Path::toString)
    if (parts.isEmpty()) return ScriptPathResult.Invalid("Script target path must not be blank")
    if (parts.dropLast(1).any { part -> part.startsWith('-') }) {
        return ScriptPathResult.Invalid("Target the disabled parent directory instead of a path inside it")
    }
    val targetName = parts.last()
    if (targetName.startsWith("--")) {
        return ScriptPathResult.Invalid("Exactly one leading '-' is reserved for disabled script targets")
    }
    val enabledName = targetName.removePrefix("-")
    if (enabledName.isEmpty()) return ScriptPathResult.Invalid("Script target name must not be '-'")
    val canonical = parts.dropLast(1)
        .fold(Path.of("")) { current, part -> current.resolve(part) }
        .resolve(enabledName)
        .invariantSeparatorsPathString
    return ScriptPathResult.Valid(canonical)
}

private val DRIVE_PREFIX: Regex = Regex("^[A-Za-z]:.*")
