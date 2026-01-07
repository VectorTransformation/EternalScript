package eternalScript.core.data

import java.io.File

enum class ScriptPrefix(val prefix: String) {
    SYNC("!"),
    IGNORE("-"),
    ;

    fun check(file: File) = check(file.nameWithoutExtension)

    fun check(other: String) = other.startsWith(prefix)
}