package eternalScript.core.data

import eternalScript.core.the.Root

enum class Config(
    val key: String,
    val value: Any,
    val comment: List<String> = emptyList()
) {
    CLASS_LOADER("class-loader", Root.ORIGIN),
    DEBUG("debug", true),
    LIBS("libs", listOf(
        "${Root.ORIGIN}/libs",
    )),
    SCRIPTS("scripts", listOf(
        "${Root.ORIGIN}/scripts",
    )),
    UTILS(
        "utils", listOf(
            "${Root.ORIGIN}/utils",
        )
    ),
    METRICS("metrics", true),
    LANG("lang", "en_US")
    ;
}