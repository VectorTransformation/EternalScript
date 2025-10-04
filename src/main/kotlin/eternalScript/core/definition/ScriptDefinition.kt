package eternalScript.core.definition

import kotlin.script.experimental.annotations.KotlinScript

@KotlinScript(
    compilationConfiguration = ScriptCompilerConfig::class,
    evaluationConfiguration = ScriptEvaluatorConfig::class
)
abstract class ScriptDefinition