package eternalScript.data

enum class Lifecycle(val function: String) {
    ENABLE("enable"),
    DISABLE("disable"),
    ;

    fun check(other: String) = function == other
}