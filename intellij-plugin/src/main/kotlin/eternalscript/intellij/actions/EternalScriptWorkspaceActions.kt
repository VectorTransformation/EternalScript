package eternalscript.intellij.actions

import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import eternalscript.intellij.EternalScriptBundle
import eternalscript.intellij.model.EternalScriptProjectService
import eternalscript.intellij.model.EternalScriptWorkspace
import java.awt.datatransfer.StringSelection

internal class ReloadEternalScriptEnvironmentAction :
    DumbAwareAction(EternalScriptBundle.message("action.reload")) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        EternalScriptProjectService.getInstance(project).apply {
            start()
            scheduleDiscovery(delay = 0)
        }
        notify(project, EternalScriptBundle.message("action.reloadRequested"), NotificationType.INFORMATION)
    }
}

internal class OpenEternalScriptManifestAction :
    DumbAwareAction(EternalScriptBundle.message("action.openManifest")) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workspace = selectedWorkspace(event) ?: return noWorkspace(project)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspace.manifest)
            ?: return noWorkspace(project)
        OpenFileDescriptor(project, file).navigate(true)
    }
}

internal class OpenEternalScriptRootAction :
    DumbAwareAction(EternalScriptBundle.message("action.openRoot")) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workspace = selectedWorkspace(event) ?: return noWorkspace(project)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspace.scriptRoot)
            ?: return noWorkspace(project)
        ProjectView.getInstance(project).select(null, root, true)
    }
}

internal class CopyEternalScriptDiagnosticsAction :
    DumbAwareAction(EternalScriptBundle.message("action.copyDiagnostics")) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val diagnostics = EternalScriptProjectService.getInstance(project).diagnosticsText()
        CopyPasteManager.getInstance().setContents(StringSelection(diagnostics))
        notify(project, EternalScriptBundle.message("action.diagnosticsCopied"), NotificationType.INFORMATION)
    }
}

private fun selectedWorkspace(event: AnActionEvent): EternalScriptWorkspace? {
    val project = event.project ?: return null
    val model = EternalScriptProjectService.getInstance(project)
    val selectedFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
    model.workspaceForSelectedFile(selectedFile)?.let { return it }
    return model.current().workspaces.firstOrNull()
}

private fun EternalScriptProjectService.workspaceForSelectedFile(file: VirtualFile?): EternalScriptWorkspace? {
    if (file == null) return null
    workspaceFor(file)?.let { return it }
    val path = runCatching { file.toNioPath().toAbsolutePath().normalize() }.getOrNull() ?: return null
    return current().workspaces.asSequence()
        .filter { workspace ->
            val workspaceRoot = workspace.manifest.parent?.parent?.parent ?: return@filter false
            path.startsWith(workspaceRoot)
        }
        .maxByOrNull { workspace -> workspace.scriptRoot.nameCount }
}

private fun noWorkspace(project: Project) {
    notify(project, EternalScriptBundle.message("action.noWorkspace"), NotificationType.WARNING)
}

private fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("EternalScript")
        .createNotification("EternalScript", content, type)
        .notify(project)
}
