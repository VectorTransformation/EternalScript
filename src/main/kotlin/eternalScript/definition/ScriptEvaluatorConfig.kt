package eternalScript.definition

import eternalScript.the.Root
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.scriptsInstancesSharing
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm

class ScriptEvaluatorConfig : ScriptEvaluationConfiguration({
    jvm {
        baseClassLoader(Root.instance().javaClass.classLoader)
        scriptsInstancesSharing(true)
    }
})