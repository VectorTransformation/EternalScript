package eternalScript.data

import eternalScript.the.Root

enum class Config(
    val key: String,
    val value: Any,
    val comment: List<String> = emptyList()
) {
    DEBUG("debug", false),
    LIBS("libs", listOf(
        "${Root.ORIGIN}/libs",
    )),
    SCRIPTS("scripts", listOf(
        "${Root.ORIGIN}/scripts",
    )),
    ;
}