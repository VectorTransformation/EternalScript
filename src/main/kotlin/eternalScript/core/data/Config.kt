package eternalScript.core.data

import eternalScript.core.runtime.PLUGIN_NAME

enum class Config(
    val key: String,
    val value: Any,
    val comment: List<String> = emptyList()
) {
    LANG(
        "lang",
        "en_US",
        listOf(
            "User-facing language: en_US, ko_KR, ja_JP, zh_CN,",
            "or the filename of a custom lang/*.json catalog."
        )
    ),
    LIBS(
        "libs",
        listOf("$PLUGIN_NAME/libs"),
        listOf("Directories containing additional compile/runtime libraries.")
    ),
    DEBUG(
        "debug",
        false,
        listOf("Log full compiler and lifecycle exception stack traces.")
    ),
    METRICS(
        "metrics",
        true,
        listOf("Send anonymous bStats usage metrics.")
    )
    ;
}
