package eternalScript.core.script.definition

import eternalScript.core.data.Resource
import eternalScript.core.extension.relativize
import eternalScript.core.extension.wrap
import eternalScript.core.manager.LangManager
import eternalScript.core.script.data.ScriptFile
import eternalScript.core.the.Root
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.util.filterByAnnotationType

object ScriptImportHandler : RefineScriptCompilationConfigurationHandler {
    override operator fun invoke(
        context: ScriptConfigurationRefinementContext
    ) = context.compilationConfiguration.with {
        val paths = context.collectedData
            ?.get(ScriptCollectedData.collectedAnnotations)
            ?.filterByAnnotationType<Import>()
            ?.flatMap { it.annotation.paths.asSequence() }
            ?.toList()
            ?: emptyList()

        ScriptImportCache.record(context.script, paths)

        val scripts = paths.flatMap { path ->
            ScriptImportCache.resolve(path) ?: run {
                val resource = Resource.SCRIPTS.child(path)
                val message =
                    LangManager.translatable("script.error.not_found").format(resource.relativize(Resource.SCRIPTS).wrap())
                Root.info(message)
                emptyList()
            }
        }
        importScripts.append(scripts.map(ScriptFile::importSource))
    }.asSuccess()
}

private fun ScriptFile.importSource() = fileSource.text.toScriptSource(importSourceName())
