package eternalScript.core.data

import eternalScript.core.the.Root

enum class Config(
    val key: String,
    val value: Any,
    val comment: List<String> = emptyList()
) {
    CLASS_LOADER("class-loader", Root.ORIGIN),
    DEBUG("debug", false),
    LIBS("libs", listOf(
        "${Root.ORIGIN}/libs",
    )),
    SCRIPTS("scripts", listOf(
        "${Root.ORIGIN}/scripts",
    )),
    METRICS("metrics", true),
    ;
}