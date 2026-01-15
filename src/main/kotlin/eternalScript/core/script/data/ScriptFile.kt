package eternalScript.core.script.data

import eternalScript.core.extension.relativize
import eternalScript.core.manager.DataManager
import java.io.File
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.host.toScriptSource

class ScriptFile(file: File) {
    val name = file.relativize()
    val fileSource = file.toScriptSource()
    val source = fileSource.text.plus(DataManager.utils()).toScriptSource(name)
}
