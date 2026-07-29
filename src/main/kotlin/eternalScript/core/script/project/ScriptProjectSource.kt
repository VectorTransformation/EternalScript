package eternalScript.core.script.project

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val PROJECT_SCRIPT_NAME = "eternal-project.kt"

data class ScriptProjectFile(
    val name: String,
    val text: String
)

data class ScriptProjectPosition(
    val sourceName: String,
    val line: Int,
    val column: Int
)

/**
 * Stable snapshot of one ordinary Kotlin source module.
 *
 * Package declarations, imports, file annotations, visibility, and
 * cross-file references keep normal Kotlin semantics. Runtime-only loader
 * code and its source map are produced lazily by [ScriptProjectModule].
 */
class ScriptProjectSource private constructor(
    val files: List<ScriptProjectFile>,
    val fingerprint: String
) {
    internal val module: ScriptProjectModule by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ScriptProjectModule.create(this)
    }

    internal fun position(
        sourceName: String,
        line: Int,
        column: Int
    ): ScriptProjectPosition? =
        module.position(sourceName, line, column)

    internal fun position(
        className: String,
        sourceName: String,
        line: Int,
        column: Int
    ): ScriptProjectPosition? =
        module.position(className, sourceName, line, column)

    companion object {
        fun compose(files: Iterable<ScriptProjectFile>): ScriptProjectSource {
            val normalizedFiles = files
                .map(ScriptProjectFile::normalized)
                .sortedWith(
                    compareBy<ScriptProjectFile> { file -> file.name.lowercase() }
                        .thenBy(ScriptProjectFile::name)
                )

            require(normalizedFiles.isNotEmpty()) {
                "An EternalScript project must contain at least one Kotlin source file."
            }

            normalizedFiles
                .groupBy { file -> file.name.lowercase() }
                .values
                .firstOrNull { duplicates -> duplicates.size > 1 }
                ?.let { duplicates ->
                    throw ScriptProjectCompositionException(
                        "Duplicate Kotlin source path: ${duplicates.joinToString { file -> file.name }}"
                    )
                }

            val digest = MessageDigest.getInstance("SHA-256")
            normalizedFiles.forEach { file ->
                digest.updateField(file.name)
                digest.updateField(file.text)
            }
            return ScriptProjectSource(
                files = normalizedFiles,
                fingerprint = digest.digest().toHexString()
            )
        }
    }
}

class ScriptProjectCompositionException(message: String) : IllegalArgumentException(message)

private fun ScriptProjectFile.normalized(): ScriptProjectFile {
    val normalizedName = name.replace('\\', '/')
    require(
        !normalizedName.startsWith("/") &&
            !WINDOWS_DRIVE_PATH.containsMatchIn(normalizedName)
    ) {
        "Kotlin source path must be relative to the project: $name"
    }
    require(normalizedName.isNotBlank()) {
        "Kotlin source path must not be blank."
    }
    require(normalizedName.none { character ->
        character == '\u0000' || character == '\r' || character == '\n'
    }) {
        "Kotlin source path contains an invalid character."
    }
    val segments = normalizedName.split('/')
    require(segments.none { segment ->
        segment.isEmpty() || segment == "." || segment == ".."
    }) {
        "Kotlin source path must stay inside the project: $name"
    }
    require(normalizedName.endsWith(".kt")) {
        "EternalScript project sources must use the lowercase .kt extension: $name"
    }

    val normalizedText = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val sourceText = if (normalizedText.startsWith('\uFEFF')) {
        " " + normalizedText.drop(1)
    } else {
        normalizedText
    }
    return ScriptProjectFile(
        name = normalizedName,
        text = sourceText
    )
}

private fun MessageDigest.updateField(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private val WINDOWS_DRIVE_PATH = Regex("""^[A-Za-z]:""")
