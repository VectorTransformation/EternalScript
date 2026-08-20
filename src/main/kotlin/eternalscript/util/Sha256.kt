package eternalscript.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object Sha256 {
    fun text(value: String): String = bytes(value.toByteArray(Charsets.UTF_8))

    fun bytes(value: ByteArray): String = MessageDigest.getInstance(ALGORITHM)
        .digest(value)
        .toHex()

    fun file(path: Path): String = digest { digest -> update(digest, path) }

    fun digest(update: (MessageDigest) -> Unit): String = MessageDigest.getInstance(ALGORITHM)
        .also(update)
        .digest()
        .toHex()

    fun update(digest: MessageDigest, path: Path) {
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private const val ALGORITHM: String = "SHA-256"
}
