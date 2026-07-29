package eternalScript.core.script.project

import eternalScript.core.script.Script

class ScriptProjectInitializationException(
    val script: Script,
    cause: Throwable
) : RuntimeException("EternalScript project initialization failed.", cause)
