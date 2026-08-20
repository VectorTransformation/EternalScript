package eternalscript.intellij.refactoring

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.util.containers.MultiMap
import eternalscript.intellij.EternalScriptBundle
import eternalscript.intellij.model.EternalScriptConflict
import eternalscript.intellij.model.EternalScriptProjectService

internal class EternalScriptRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean =
        EternalScriptProjectService.getInstance(element.project).sourceDeclaration(element) != null

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement =
        EternalScriptProjectService.getInstance(element.project).sourceDeclaration(element) ?: element

    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean
    ): Collection<PsiReference> {
        val model = EternalScriptProjectService.getInstance(element.project)
        val source = model.sourceDeclaration(element) ?: return emptyList()
        return model.findReferences(source, searchScope)
    }

    override fun findExistingNameConflicts(
        element: PsiElement,
        newName: String,
        conflicts: MultiMap<PsiElement, String>
    ) {
        super.findExistingNameConflicts(element, newName, conflicts)
        val model = EternalScriptProjectService.getInstance(element.project)
        val source = model.sourceDeclaration(element) ?: return
        model.renameConflicts(source, newName).forEach { conflict ->
            val message = when (conflict) {
                is EternalScriptConflict.DuplicateDeclaration -> EternalScriptBundle.message(
                    "rename.conflict.declaration",
                    conflict.name,
                    conflict.paths.joinToString()
                )
                is EternalScriptConflict.ConflictingImport -> EternalScriptBundle.message(
                    "rename.conflict.import",
                    conflict.name,
                    conflict.imports.joinToString()
                )
            }
            conflicts.putValue(source, message)
        }
    }

    override fun isInplaceRenameSupported(): Boolean = false
}
