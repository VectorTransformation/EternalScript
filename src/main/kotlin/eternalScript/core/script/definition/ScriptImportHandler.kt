package eternalScript.core.script.definition

import eternalScript.core.data.Resource
import eternalScript.core.script.data.ScriptSuffix
import eternalScript.core.extension.relativize
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.extension.wrap
import eternalScript.core.manager.LangManager
import eternalScript.core.script.data.ScriptFile
import eternalScript.core.the.Root
import kotlin.script.experimental.api.*
import kotlin.script.experimental.util.filterByAnnotationType

object ScriptImportHandler : RefineScriptCompilationConfigurationHandler {
    override operator fun invoke(
        context: ScriptConfigurationRefinementContext
    ) = context.compilationConfiguration.with {
        val scripts = buildList {
            context.collectedData
                ?.get(ScriptCollectedData.collectedAnnotations)
                ?.filterByAnnotationType<Import>()
                ?.forEach { import ->
                    import.annotation.paths.forEach { path ->
                        val resource = Resource.PLUGINS.child(path)

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
                                add(ScriptFile(file).fileSource)
                            }
                        } else {
                            add(ScriptFile(resource).fileSource)
                        }
                    }
                }
            }
        importScripts.append(scripts)
    }.asSuccess()
}