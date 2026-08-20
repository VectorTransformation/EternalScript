package eternalscript.intellij.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import eternalscript.intellij.model.EternalScriptProjectService

internal class EternalScriptStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        EternalScriptProjectService.getInstance(project).start()
    }
}
