package eternalscript.intellij.diagnostics

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import eternalscript.intellij.EternalScriptBundle
import eternalscript.intellij.model.EternalScriptConflict
import eternalscript.intellij.model.EternalScriptProjectService

internal class EternalScriptConflictAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val conflict = EternalScriptProjectService.getInstance(element.project).conflictFor(element) ?: return
        val message = when (conflict) {
            is EternalScriptConflict.DuplicateDeclaration -> EternalScriptBundle.message(
                "diagnostic.duplicate.declaration",
                conflict.name,
                conflict.paths.joinToString()
            )
            is EternalScriptConflict.ConflictingImport -> EternalScriptBundle.message(
                "diagnostic.conflicting.import",
                conflict.name,
                conflict.imports.joinToString()
            )
        }
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(element).create()
    }
}
