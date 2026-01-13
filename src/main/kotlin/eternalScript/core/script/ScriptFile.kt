package eternalScript.core.script

import eternalScript.core.extension.relativize
import eternalScript.core.manager.DataManager
import java.io.File
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.host.toScriptSource

class ScriptFile(file: File) {
    val name: String by lazy {
        file.relativize()
    }
    val fileSource: SourceCode by lazy {
        file.toScriptSource()
    }
    val source: SourceCode by lazy {
        fileSource.text.plus(DataManager.utils()).toScriptSource(name)
    }
}
