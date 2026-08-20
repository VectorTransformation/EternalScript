package eternalscript.intellij.refactoring

import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import eternalscript.intellij.model.EternalScriptProjectService

/** Adds source-native cross-script references to IntelliJ's standard search pipeline. */
internal class EternalScriptReferencesSearchExecutor :
    QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    override fun execute(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ): Boolean {
        val element = queryParameters.elementToSearch
        val model = EternalScriptProjectService.getInstance(element.project)
        val source = model.sourceDeclaration(element) ?: return true
        return model.findReferences(source, queryParameters.effectiveSearchScope).all(consumer::process)
    }
}
