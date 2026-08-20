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

internal fun isEternalScriptFile(file: File): Boolean =
    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(file.toPath()) &&
        file.name.endsWith(".$ETERNAL_SCRIPT_EXTENSION")

internal fun readScriptSources(root: File): List<ScriptSourceFile> = walkScripts(root)
    .map { file -> ScriptSourceFile(file.toFile(), logicalScriptPath(file, root.toPath()), Files.readString(file)) }
    .sortedWith(compareBy({ source -> source.name.lowercase(Locale.ROOT) }, ScriptSourceFile::name))
    .toList()

internal fun discoverScriptTargets(root: File): List<String> {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    check(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
        "Scripts path is not a readable directory: $rootPath"
    }
    val targets = linkedSetOf<String>()
    Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (Files.isSymbolicLink(directory) || attributes.isOther) return FileVisitResult.SKIP_SUBTREE
            if (directory == rootPath) return FileVisitResult.CONTINUE
            val logical = logicalScriptPath(directory, rootPath)
            if (directory.fileName.toString().startsWith('-')) {
                if (directory.fileName.toString().isSingleDisabledName()) {
                    targets += canonicalDisabledPath(logical)
                }
                return FileVisitResult.SKIP_SUBTREE
            }
            targets += logical
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            val name = file.fileName.toString()
            val enabledScript = !name.startsWith('-') && name.endsWith(".$ETERNAL_SCRIPT_EXTENSION")
            val disabledScript = name.isSingleDisabledName() &&
                name.removePrefix("-").endsWith(".$ETERNAL_SCRIPT_EXTENSION")
            if (attributes.isRegularFile && !attributes.isSymbolicLink && (enabledScript || disabledScript)) {
                val logical = logicalScriptPath(file, rootPath)
                targets += if (disabledScript) canonicalDisabledPath(logical) else logical
            }
            return FileVisitResult.CONTINUE
        }
    })
    return targets.sortedWith(compareBy(String::lowercase, { it }))
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

private fun walkScripts(root: File): Sequence<Path> {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    check(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
        "Scripts path is not a readable directory: $rootPath"
    }
    check(Files.isReadable(rootPath)) { "Scripts directory is not readable: $rootPath" }
    val files = mutableListOf<Path>()
    Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (directory != rootPath && directory.fileName.toString().startsWith('-')) {
                return FileVisitResult.SKIP_SUBTREE
            }
            if (Files.isSymbolicLink(directory) || attributes.isOther) {
                return FileVisitResult.SKIP_SUBTREE
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (
                attributes.isRegularFile &&
                !attributes.isSymbolicLink &&
                !file.fileName.toString().startsWith('-') &&
                file.fileName.toString().endsWith(".$ETERNAL_SCRIPT_EXTENSION")
            ) {
                files.add(file)
            }
            return FileVisitResult.CONTINUE
        }
    })
    return files.asSequence()
}
