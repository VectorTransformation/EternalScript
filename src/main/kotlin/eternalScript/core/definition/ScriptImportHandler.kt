package eternalScript.core.definition

import eternalScript.core.data.Resource
import eternalScript.core.extension.wrap
import eternalScript.core.manager.DataManager
import eternalScript.core.manager.LangManager
import eternalScript.core.the.Root
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.util.filterByAnnotationType

class ScriptImportHandler : RefineScriptCompilationConfigurationHandler {
    override operator fun invoke(
        context: ScriptConfigurationRefinementContext
    ): ResultWithDiagnostics<ScriptCompilationConfiguration> = parser(context)

    fun parser(
        context: ScriptConfigurationRefinementContext
    ): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val annotationList =
            context.collectedData?.get(ScriptCollectedData.collectedAnnotations)
                ?.takeUnless { it.isEmpty() }
                ?.filterByAnnotationType<Import>()
                ?: return context.compilationConfiguration.asSuccess()

        val sources = linkedSetOf<SourceCode>()

        annotationList.forEach { import ->
            import.annotation.script.forEach { script ->
                    val file = Resource.PLUGINS.child(script)

                    if (!file.exists()) {
                        val message =
                            LangManager.translatable("script.not_found").format(DataManager.scriptPath(file).wrap())
                        Root.info(message)
                        return@forEach
                    }

                    sources.add(file.toScriptSource())
                }
        }

        return ScriptCompilationConfiguration(context.compilationConfiguration) {
            importScripts.append(sources)
        }.asSuccess()
    }
}