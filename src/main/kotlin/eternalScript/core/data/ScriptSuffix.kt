package eternalScript.core.data

import java.io.File

enum class ScriptSuffix(vararg val suffix: String) {
    SCRIPT("kt", "kts"),
    LANG("json")
    ;

    fun check(file: File) = file.extension in suffix
}