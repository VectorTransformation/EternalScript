package eternalScript.core.definition

import eternalScript.core.data.Config
import eternalScript.core.manager.ConfigManager
import eternalScript.core.the.Root
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.scriptsInstancesSharing
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.loadDependencies

class ScriptEvaluatorConfig : ScriptEvaluationConfiguration({
    jvm {
        baseClassLoader(Root.classLoader(ConfigManager.value(Config.CLASS_LOADER)))
        loadDependencies(false)
        scriptsInstancesSharing(true)
    }
})