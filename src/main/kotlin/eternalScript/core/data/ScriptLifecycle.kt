package eternalScript.core.data

enum class ScriptLifecycle(val function: String) {
    ENABLE("enable"),
    DISABLE("disable"),
    ;

    fun check(other: String) = function == other
}