package eternalscript.intellij.model

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import eternalscript.intellij.refactoring.EternalScriptReferenceIndex
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

internal class EternalScriptProjectQueries(
    private val project: Project,
    private val snapshot: () -> EternalScriptProjectSnapshot,
    private val references: EternalScriptReferenceIndex
) {
    fun workspaceFor(file: VirtualFile): EternalScriptWorkspace? = snapshot().workspaceFor(file)

    fun conflictFor(element: PsiElement): EternalScriptConflict? {
        val target = when (element) {
            is KtNamedDeclaration -> element
            is KtImportDirective -> element
            else -> return null
        }
        val file = target.containingFile.virtualFile ?: return null
        val workspace = workspaceFor(file)?.takeIf { candidate -> file.url in candidate.sourceUrls } ?: return null
        return workspace.conflicts[EternalScriptSourceLocation(file.url, target.textOffset)]
    }

    fun sourceForGenerated(element: PsiElement): KtNamedDeclaration? {
        val containingFile = element.containingFile ?: return null
        val workspace = snapshot().workspaces.firstOrNull { candidate ->
            candidate.generatedFile(containingFile.name) != null
        } ?: return null
        val generatedFile = workspace.generatedFile(containingFile.name) ?: return null
        val offset = element.textOffset
        val declarationName = (element as? KtNamedDeclaration
            ?: element.getParentOfType<KtNamedDeclaration>(strict = false))?.name
        val sameName = declarationName?.let { name ->
            generatedFile.mappings.filter { candidate -> candidate.sourcePointer.element?.name == name }
        }.orEmpty()
        val mapping = sameName.firstOrNull { candidate -> candidate.range.containsOffset(offset) }
            ?: sameName.singleOrNull()
            ?: generatedFile.mappings.firstOrNull { candidate -> candidate.range.containsOffset(offset) }
            ?: return null
        return mapping.sourcePointer.element ?: sourceDeclaration(mapping.source)
    }

    fun sourceDeclaration(element: PsiElement): KtNamedDeclaration? {
        sourceForGenerated(element)?.let { return it }
        val declaration = element as? KtNamedDeclaration ?: element.getParentOfType<KtNamedDeclaration>(strict = false)
        val file = declaration?.containingKtFile?.virtualFile ?: return null
        val workspace = workspaceFor(file) ?: return null
        return declaration.takeIf { file.url in workspace.sourceUrls }
    }

    fun findReferences(
        targetElement: PsiElement,
        scope: SearchScope? = null
    ): List<PsiReference> {
        val target = sourceDeclaration(targetElement) ?: return emptyList()
        val targetFile = target.containingKtFile.virtualFile ?: return emptyList()
        val workspace = workspaceFor(targetFile) ?: return emptyList()
        val effectiveScope = if (scope is GlobalSearchScope) {
            val workspaceFiles = workspace.sourceUrls.mapNotNull(VirtualFileManager.getInstance()::findFileByUrl)
            scope.uniteWith(GlobalSearchScope.filesScope(project, workspaceFiles))
        } else {
            scope
        }
        return references.findReferences(workspace.id, target, effectiveScope, ::sourceDeclaration)
    }

    fun renameConflicts(targetElement: PsiElement, newName: String): List<EternalScriptConflict> {
        val target = sourceDeclaration(targetElement) ?: return emptyList()
        val targetFile = target.containingKtFile.virtualFile ?: return emptyList()
        val workspace = workspaceFor(targetFile) ?: return emptyList()
        val pathByUrl = linkedMapOf<String, String>()
        val ideAbis = workspace.sourceUrls.mapNotNull { url ->
            val abi = workspace.fileAbis[url] ?: return@mapNotNull null
            val path = VirtualFileManager.getInstance().findFileByUrl(url)?.let { file ->
                runCatching {
                    workspace.scriptRoot.relativize(file.toNioPath().toAbsolutePath().normalize())
                        .toString()
                        .replace('\\', '/')
                }.getOrNull()
            } ?: url
            pathByUrl[url] = path
            path to abi
        }.toMap()
        val importScopePaths = buildSet {
            add(pathByUrl[targetFile.url] ?: targetFile.url)
            findReferences(target).forEach { reference ->
                val fileUrl = reference.element.containingFile?.virtualFile?.url ?: return@forEach
                add(pathByUrl[fileUrl] ?: fileUrl)
            }
        }
        val sharedDeclarationPaths = if (targetFile.url in workspace.activeSourceUrls) {
            workspace.activeSourceUrls.mapNotNullTo(linkedSetOf(), pathByUrl::get)
        } else {
            emptySet()
        }
        return EternalScriptConflictAnalyzer.renameConflicts(
            ideAbis,
            sharedDeclarationPaths,
            importScopePaths,
            target,
            newName,
            workspace.environment.defaultImports()
        )
    }

    private fun sourceDeclaration(location: EternalScriptSourceLocation): KtNamedDeclaration? {
        val file = VirtualFileManager.getInstance().findFileByUrl(location.fileUrl) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val leaf = psiFile.findElementAt(location.offset) ?: return null
        return leaf.getParentOfType(strict = false)
    }
}
