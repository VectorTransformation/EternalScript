package eternalscript.intellij.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import eternalscript.intellij.model.EternalScriptDeclarationRenderer
import eternalscript.intellij.model.EternalScriptFileAbi
import eternalscript.intellij.model.EternalScriptRenderedDeclaration
import org.jetbrains.kotlin.psi.KtFile

internal class AbiAnalyzer(private val project: Project) {
    fun analyze(
        file: IndexedScriptFile,
        previous: EternalScriptFileAbi?,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>> = emptyMap()
    ): EternalScriptFileAbi? {
        val action: () -> EternalScriptFileAbi? = action@{
            val virtualFile = VirtualFileManager.getInstance().findFileByUrl(file.url) ?: return@action null
            val psi = findKtFile(virtualFile) ?: return@action null
            ProgressManager.checkCanceled()
            EternalScriptDeclarationRenderer.renderFile(
                psi,
                previous,
                availableDeclarations
            )
        }
        return if (ApplicationManager.getApplication().isReadAccessAllowed) {
            action()
        } else {
            ReadAction.computeCancellable<EternalScriptFileAbi?, RuntimeException> {
                val virtualFile = VirtualFileManager.getInstance().findFileByUrl(file.url)
                    ?: return@computeCancellable null
                val psi = findKtFile(virtualFile) ?: return@computeCancellable null
                ProgressManager.checkCanceled()
                EternalScriptDeclarationRenderer.renderFile(
                    psi,
                    previous,
                    availableDeclarations
                )
            }
        }
    }

    private fun findKtFile(virtualFile: VirtualFile): KtFile? {
        val fileDocuments = FileDocumentManager.getInstance()
        val document = fileDocuments.unsavedDocuments.firstOrNull { candidate ->
            fileDocuments.getFile(candidate)?.url == virtualFile.url
        } ?: fileDocuments.getCachedDocument(virtualFile)
            ?: fileDocuments.getDocument(virtualFile)
        val documentPsi = document?.let { value ->
            PsiDocumentManager.getInstance(project).getPsiFile(value) as? KtFile
        }
        return documentPsi ?: PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
    }
}
