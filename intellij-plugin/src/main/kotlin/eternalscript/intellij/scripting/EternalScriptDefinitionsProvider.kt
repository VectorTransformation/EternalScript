package eternalscript.intellij.scripting

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import eternalscript.intellij.model.EternalScriptProjectService
import eternalscript.intellij.model.EternalScriptWorkspace
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.api.displayName
import kotlin.script.experimental.api.fileExtension
import kotlin.script.experimental.api.filePathPattern
import kotlin.script.experimental.api.isStandalone
import kotlin.script.experimental.host.ScriptDefinition
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.intellij.ScriptDefinitionsProvider
import java.nio.file.Path
import java.util.regex.Pattern

/** Project definitions are recomputed when the validated workspace registry changes. */
internal class EternalScriptDefinitionsProvider(project: Project) : ScriptDefinitionsProvider {
    private val service = EternalScriptProjectService.getInstance(project)

    override val id: String = "eternalscript"
    override fun getDefinitionClasses(): Iterable<String> = emptyList()
    override fun getDefinitionsClassPath(): Iterable<java.io.File> = emptyList()
    override fun useDiscovery(): Boolean = false

    override fun provideDefinitions(
        baseHostConfiguration: ScriptingHostConfiguration,
        loadedScriptDefinitions: List<ScriptDefinition>
    ): Iterable<ScriptDefinition> {
        service.start()
        service.scriptDefinitionRegistered()
        return service.definitionWorkspaces().distinctBy(EternalScriptWorkspace::id).map(::definition)
    }

    private fun definition(workspace: EternalScriptWorkspace): ScriptDefinition = ScriptDefinition(
        kotlin.script.experimental.api.ScriptCompilationConfiguration(
            service.definitionConfiguration(workspace)
        ) {
            displayName("EternalScript")
            baseClass(KotlinType("eternalscript.api.script.Script"))
            fileExtension("eternal.kts")
            filePathPattern(rootPattern(workspace.scriptRoot))
            isStandalone(false)
        },
        ScriptEvaluationConfiguration {}
    )

    private fun rootPattern(root: Path): String {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            return "^.*[\\\\/]scripts[\\\\/].*\\.eternal\\.kts$"
        }
        val normalized = root.toAbsolutePath().normalize()
        val systemPath = Pattern.quote(normalized.toString())
        val slashPath = Pattern.quote(normalized.toString().replace('\\', '/'))
        val uri = Pattern.quote(normalized.toUri().toASCIIString().trimEnd('/'))
        return "^(?:$systemPath|$slashPath|$uri)[\\\\/].*\\.eternal\\.kts$"
    }
}
