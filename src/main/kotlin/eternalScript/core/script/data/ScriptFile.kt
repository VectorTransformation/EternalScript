package eternalScript.core.script.data

import eternalScript.core.data.Resource
import eternalScript.core.extension.relativize
import java.io.File
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.host.toScriptSource

class ScriptFile(file: File) {
    val name = file.relativize(Resource.SCRIPTS)
    val fileSource = file.toScriptSource()
    val source = fileSource.text.toScriptSource(name)
}
