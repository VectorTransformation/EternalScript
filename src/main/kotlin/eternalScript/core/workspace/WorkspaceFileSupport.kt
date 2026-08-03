package eternalScript.core.workspace

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal fun normalizeClasspath(entries: Iterable<Path>): List<Path> =
    entries.asSequence()
        .map { it.toAbsolutePath().normalize() }
        .filter { path ->
            val value = path.toString()
            '\r' !in value && '\n' !in value
        }
        .distinct()
        .toList()

internal fun sameContent(path: Path, expected: ByteArray): Boolean {
    if (Files.isSymbolicLink(path)) return false
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
    if (Files.size(path) != expected.size.toLong()) return false
    return sha256(path) == sha256(expected)
}

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

internal fun sha256(content: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(content).toHex()

internal fun Throwable.toWorkspaceError(operation: String? = null): String {
    val detail = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
    return if (operation == null) detail else "Failed to $operation: $detail"
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
