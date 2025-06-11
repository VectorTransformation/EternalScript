package eternalScript.definition

import eternalScript.data.Resource
import eternalScript.extension.toComponent
import eternalScript.extension.wrap
import eternalScript.manager.DataManager
import eternalScript.the.Root
import kotlin.script.experimental.api.RefineScriptCompilationConfigurationHandler
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCollectedData
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptConfigurationRefinementContext
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.collectedAnnotations
import kotlin.script.experimental.api.importScripts
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.util.filterByAnnotationType
import kotlin.sequences.forEach

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
            import.annotation.script
                .asSequence()
                .forEach { script ->
                    val file = Resource.SCRIPTS.child(script)

                    if (!file.exists()) {
                        Root.info("Script Not Found - ${DataManager.scriptPath(file).wrap()}".toComponent())
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