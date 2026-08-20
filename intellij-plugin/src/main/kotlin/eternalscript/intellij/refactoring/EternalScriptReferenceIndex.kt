package eternalscript.intellij.refactoring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import eternalscript.intellij.model.EternalScriptFileAbi
import eternalscript.intellij.model.EternalScriptProjectSnapshot
import eternalscript.intellij.model.EternalScriptReferenceCandidate
import eternalscript.intellij.model.EternalScriptWorkspace
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.util.concurrent.atomic.AtomicReference

internal class EternalScriptReferenceIndex {
    private data class FileReferences(
        val contentStamp: Long,
        val contentDigest: String,
        val byName: Map<String, List<EternalScriptReferenceCandidate>>
    ) {
        fun matches(abi: EternalScriptFileAbi): Boolean =
            contentStamp == abi.contentStamp && contentDigest == abi.contentDigest

        companion object {
            fun from(abi: EternalScriptFileAbi): FileReferences = FileReferences(
                abi.contentStamp,
                abi.contentDigest,
                abi.referenceCandidates.groupBy(EternalScriptReferenceCandidate::name)
            )
        }
    }

    private data class WorkspaceReferences(
        val files: Map<String, FileReferences>,
        val byName: Map<String, List<EternalScriptReferenceCandidate>>
    )

    private val workspaces = AtomicReference<Map<String, WorkspaceReferences>>(emptyMap())

    @Synchronized
    fun publish(snapshot: EternalScriptProjectSnapshot) {
        val previous = workspaces.get()
        workspaces.set(snapshot.workspaces.associate { workspace ->
            workspace.id to updateWorkspace(previous[workspace.id], workspace)
        })
    }

    fun findReferences(
        workspaceId: String,
        target: KtNamedDeclaration,
        scope: SearchScope?,
        canonicalDeclaration: (PsiElement) -> KtNamedDeclaration?
    ): List<PsiReference> {
        val name = target.name ?: return emptyList()
        val targetFile = target.containingKtFile.virtualFile ?: return emptyList()
        val candidates = workspaces.get()[workspaceId]?.byName?.get(name).orEmpty()
        val action = {
            val references = linkedMapOf<String, PsiReference>()
            candidates.forEach { candidate ->
                ProgressManager.checkCanceled()
                if (candidate.sourceUrl == targetFile.url) return@forEach
                val expression = candidate.pointer.element ?: return@forEach
                val file = expression.containingFile.virtualFile ?: return@forEach
                if (scope != null && !scope.contains(file)) return@forEach
                if (scope is LocalSearchScope && !scope.containsRange(expression.containingFile, expression.textRange)) {
                    return@forEach
                }
                val reference = expression.mainReference
                val resolved = reference.resolve() ?: return@forEach
                val canonical = canonicalDeclaration(resolved) ?: return@forEach
                if (!canonical.manager.areElementsEquivalent(canonical, target)) return@forEach
                val key = "${file.url}:${reference.rangeInElement.startOffset + expression.textOffset}:" +
                    reference.rangeInElement.endOffset
                references.putIfAbsent(key, reference)
            }
            references.values.toList()
        }
        return if (ApplicationManager.getApplication().isReadAccessAllowed) action()
        else ReadAction.computeCancellable<List<PsiReference>, RuntimeException>(action)
    }

    private fun updateWorkspace(
        previous: WorkspaceReferences?,
        workspace: EternalScriptWorkspace
    ): WorkspaceReferences {
        val files = workspace.sourceUrls.mapNotNull { url ->
            val abi = workspace.fileAbis[url] ?: return@mapNotNull null
            val old = previous?.files?.get(url)
            url to if (old?.matches(abi) == true) old else FileReferences.from(abi)
        }.toMap(linkedMapOf())
        if (previous == null) return WorkspaceReferences(files, groupByName(files.values))

        val changedUrls = (previous.files.keys + files.keys).filterTo(linkedSetOf()) { url ->
            previous.files[url] !== files[url]
        }
        if (changedUrls.isEmpty()) return previous

        val affectedNames = changedUrls.asSequence().flatMap { url ->
            sequenceOf(previous.files[url], files[url])
                .filterNotNull()
                .flatMap { file -> file.byName.keys.asSequence() }
        }.toCollection(linkedSetOf())
        val byName = previous.byName.toMutableMap()
        affectedNames.forEach { name ->
            val candidates = buildList {
                previous.byName[name].orEmpty()
                    .filterTo(this) { candidate -> candidate.sourceUrl !in changedUrls }
                changedUrls.forEach { url ->
                    files[url]?.byName?.get(name)?.let { changed -> addAll(changed) }
                }
            }
            if (candidates.isEmpty()) byName.remove(name) else byName[name] = candidates
        }
        return WorkspaceReferences(files, byName)
    }

    private fun groupByName(files: Collection<FileReferences>): Map<String, List<EternalScriptReferenceCandidate>> =
        files.asSequence()
            .flatMap { file -> file.byName.values.asSequence().flatten() }
            .groupBy(EternalScriptReferenceCandidate::name)
}
