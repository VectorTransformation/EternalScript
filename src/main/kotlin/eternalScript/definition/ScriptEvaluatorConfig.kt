package eternalScript.definition

import eternalScript.data.Config
import eternalScript.manager.ConfigManager
import eternalScript.the.Root
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.scriptsInstancesSharing
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm

class ScriptEvaluatorConfig : ScriptEvaluationConfiguration({
    jvm {
        baseClassLoader(Root.classLoader(ConfigManager.value(Config.CLASS_LOADER)))
        scriptsInstancesSharing(true)
    }
})