package eternalscript.scripting.repl

import eternalscript.util.Sha256

internal data class SharedReplSource(
    val name: String,
    val text: String
) {
    val hash: String by lazy(LazyThreadSafetyMode.NONE) { Sha256.text(text) }
}

internal data class SharedReplDiagnostic(
    val source: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
    val cause: Throwable? = null
)
