package eternalScript.data

enum class ScriptPrefix(val prefix: String) {
    SYNC("!"),
    IGNORE("-"),
    ;

    fun check(other: String) = other.startsWith(prefix)

    fun replaceFirst(other: String, newValue: String) = other.replaceFirst(prefix, newValue)
}