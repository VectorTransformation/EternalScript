package eternalscript.scripting.cache

import java.io.File

internal object ScriptCacheLayout {
    const val CURRENT_FORMAT: Int = 5

    private const val DIRECTORY_PREFIX: String = "scripts-v"

    val currentDirectoryName: String = "$DIRECTORY_PREFIX$CURRENT_FORMAT"

    fun currentDirectory(cacheDirectory: File): File =
        File(cacheDirectory.absoluteFile.normalize(), currentDirectoryName)
}
