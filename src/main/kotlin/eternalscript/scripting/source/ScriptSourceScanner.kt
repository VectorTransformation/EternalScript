package eternalscript.scripting.source

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlin.io.path.invariantSeparatorsPathString

internal data class ScriptSourceFile(
    val file: File,
    val name: String,
    val text: String
)

internal data class DiscoveredScriptTarget(
    val path: String,
    val kind: ScriptTargetKind,
    val enabled: Boolean
)

internal data class ScriptSourceScan(
    val sources: List<ScriptSourceFile>,
    val targets: List<DiscoveredScriptTarget>
)

internal fun readScriptSources(root: File): List<ScriptSourceFile> = scanScriptSources(root).sources

internal fun discoverScriptTargets(root: File): List<String> =
    discoverScriptTargetEntries(root).map(DiscoveredScriptTarget::path)

internal fun discoverScriptTargetEntries(root: File): List<DiscoveredScriptTarget> =
    scanScriptSources(root).targets

internal fun scanScriptSources(root: File): ScriptSourceScan {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    check(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
        "Scripts path is not a readable directory: $rootPath"
    }
    check(Files.isReadable(rootPath)) { "Scripts directory is not readable: $rootPath" }
    val sources = mutableListOf<ScriptSourceFile>()
    val targets = linkedMapOf<String, DiscoveredScriptTarget>()
    Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (Files.isSymbolicLink(directory) || attributes.isOther) return FileVisitResult.SKIP_SUBTREE
            if (directory == rootPath) return FileVisitResult.CONTINUE
            val logical = logicalScriptPath(directory, rootPath)
            if (directory.fileName.toString().startsWith('-')) {
                if (directory.fileName.toString().isSingleDisabledName()) {
                    val path = canonicalDisabledPath(logical)
                    targets[path] = DiscoveredScriptTarget(path, ScriptTargetKind.DIRECTORY, false)
                }
                return FileVisitResult.SKIP_SUBTREE
            }
            targets[logical] = DiscoveredScriptTarget(logical, ScriptTargetKind.DIRECTORY, true)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            val name = file.fileName.toString()
            val enabledScript = !name.startsWith('-') && name.endsWith(".$ETERNAL_SCRIPT_EXTENSION")
            val disabledScript = name.isSingleDisabledName() &&
                name.removePrefix("-").endsWith(".$ETERNAL_SCRIPT_EXTENSION")
            if (attributes.isRegularFile && !attributes.isSymbolicLink && (enabledScript || disabledScript)) {
                val logical = logicalScriptPath(file, rootPath)
                val path = if (disabledScript) canonicalDisabledPath(logical) else logical
                targets[path] = DiscoveredScriptTarget(path, ScriptTargetKind.FILE, !disabledScript)
                if (enabledScript) {
                    sources += ScriptSourceFile(file.toFile(), logical, Files.readString(file))
                }
            }
            return FileVisitResult.CONTINUE
        }
    })
    return ScriptSourceScan(
        sources.sortedWith(compareBy({ source -> source.name.lowercase(Locale.ROOT) }, ScriptSourceFile::name)),
        targets.values.sortedWith(compareBy({ it.path.lowercase(Locale.ROOT) }, { it.path }))
    )
}

internal fun logicalScriptPath(file: Path, root: Path): String {
    val normalized = file.toAbsolutePath().normalize()
    val normalizedRoot = root.toAbsolutePath().normalize()
    check(normalized.startsWith(normalizedRoot)) { "Script is outside the scripts directory: $file" }
    return normalizedRoot.relativize(normalized).invariantSeparatorsPathString
}

private fun canonicalDisabledPath(path: String): String {
    val parsed = Path.of(path)
    return parsed.parent
        ?.resolve(parsed.fileName.toString().removePrefix("-"))
        ?.invariantSeparatorsPathString
        ?: parsed.fileName.toString().removePrefix("-")
}

private fun String.isSingleDisabledName(): Boolean =
    startsWith('-') && !startsWith("--") && length > 1
