package eternalScript.core.data

enum class ScriptPrefix(val prefix: String) {
    SYNC("!"),
    IGNORE("-"),
    ;

    fun check(other: String) = other.startsWith(prefix)
}