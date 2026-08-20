package eternalscript.intellij.model

import java.nio.file.Path

internal object EternalScriptIncrementalPlanner {
    fun isActivePath(root: Path, path: Path): Boolean = root.relativize(path).none { segment ->
        segment.toString().startsWith('-')
    }

    fun isVisibleToIde(root: Path, path: Path): Boolean {
        val relative = runCatching { root.relativize(path) }.getOrNull() ?: return false
        return relative.nameCount > 0 && !relative.startsWith("..")
    }
}
