package eternalScript.core.script.definition

import eternalScript.core.data.Resource
import eternalScript.core.extension.wrap
import eternalScript.core.manager.DataManager
import eternalScript.core.manager.LangManager
import eternalScript.core.the.Root
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.util.filterByAnnotationType

object ScriptImportHandler : RefineScriptCompilationConfigurationHandler {
    override operator fun invoke(context: ScriptConfigurationRefinementContext) = parser(context)

    fun parser(
        context: ScriptConfigurationRefinementContext
    ): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val annotations = context.collectedData
                ?.get(ScriptCollectedData.collectedAnnotations)
                ?.filterByAnnotationType<Import>()
                ?.takeUnless(List<*>::isEmpty)
                ?: return context.compilationConfiguration.asSuccess()

        val sources = annotations
            .flatMap { import ->
                import.annotation.script.mapNotNull { script ->
                    val file = Resource.PLUGINS.child(script)

                    if (!file.exists()) {
                        val message =
                            LangManager.translatable("script.not_found").format(DataManager.scriptPath(file).wrap())
                        Root.info(message)
                        return@mapNotNull null
                    }

                    file.readText().toScriptSource()
                }
            }.distinct()

        return context.compilationConfiguration.with {
            importScripts.append(sources)
        }.asSuccess()
    }
}