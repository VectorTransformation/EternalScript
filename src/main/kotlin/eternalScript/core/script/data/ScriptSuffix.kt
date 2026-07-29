package eternalScript.core.script.data

import java.io.File

enum class ScriptSuffix(
    private val ignoreCase: Boolean,
    vararg val suffix: String
) {
    SCRIPT(false, "kt"),
    LANG(true, "json")
    ;

    fun check(file: File) = suffix.any { value ->
        file.name.endsWith(".$value", ignoreCase = ignoreCase)
    }
}
