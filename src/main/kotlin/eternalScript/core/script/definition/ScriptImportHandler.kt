package eternalScript.core.script.definition

import eternalScript.core.data.Resource
import eternalScript.core.data.ScriptSuffix
import eternalScript.core.extension.relativize
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.extension.wrap
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

        val sources = mutableListOf<SourceCode>()

        annotations.forEach { import ->
            import.annotation.paths.forEach { path ->
                val resource = Resource.PLUGINS.child(path)

                println(resource.name)

                if (!resource.exists()) {
                    val message =
                        LangManager.translatable("script.not_found").format(resource.relativize().wrap())
                    Root.info(message)
                    return@forEach
                }

                if (resource.isDirectory) {
                    resource.searchAllSequence(
                        { file ->
                            if (!ScriptSuffix.SCRIPT.check(file)) return@searchAllSequence false
                            true
                        }
                    ).forEach { file ->
                        val code = file.toScriptSource()
                        sources.add(code)
                    }
                } else {
                    val code = resource.toScriptSource()
                    sources.add(code)
                }
            }
        }

        return context.compilationConfiguration.with {
            importScripts.append(sources)
        }.asSuccess()
    }
}