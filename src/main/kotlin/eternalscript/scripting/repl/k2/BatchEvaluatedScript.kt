package eternalscript.scripting.repl.k2

import eternalscript.api.script.Script

internal data class BatchEvaluatedScript(
    val compiled: BatchCompiledScript,
    val instance: Any,
    val script: Script,
    val value: Any?
)
