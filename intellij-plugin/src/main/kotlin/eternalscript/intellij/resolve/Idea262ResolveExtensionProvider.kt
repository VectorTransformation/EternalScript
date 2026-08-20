package eternalscript.intellij.resolve

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import eternalscript.intellij.model.EternalScriptGeneratedFile
import eternalscript.intellij.model.EternalScriptProjectService
import eternalscript.intellij.model.EternalScriptWorkspace
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaScriptModule
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtension
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionFile
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionNavigationTargetsProvider
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

@OptIn(KaSpiExtensionPoint::class, KaPlatformInterface::class, KaExperimentalApi::class)
internal class Idea262ResolveExtensionProvider(
    private val project: Project
) : KaResolveExtensionProvider() {
    override fun provideExtensionsFor(module: KaModule): List<KaResolveExtension> {
        if (module.project !== project || project.isDisposed) return emptyList()
        val model = EternalScriptProjectService.getInstance(project)
        model.start()
        val snapshot = model.current()
        val useSiteModule = baseContextModule(module)
        val relevant = when {
            useSiteModule is KaScriptModule -> useSiteModule.file.virtualFile
                ?.let(snapshot::workspaceFor)
                ?.let(::listOf)
                .orEmpty()
            else -> snapshot.workspaces.filter { workspace -> workspaceBelongsToModule(useSiteModule, workspace) }
        }
        if (relevant.isEmpty()) return emptyList()
        return listOf(Idea262ResolveExtension(relevant))
    }
}

@OptIn(KaPlatformInterface::class)
private tailrec fun baseContextModule(module: KaModule): KaModule =
    if (module is KaDanglingFileModule) baseContextModule(module.contextModule) else module

private fun workspaceBelongsToModule(module: KaModule, workspace: EternalScriptWorkspace): Boolean {
    val root = LocalFileSystem.getInstance().findFileByNioFile(workspace.scriptRoot)
    if (root != null && moduleBaseContentScopeContains(module, root)) return true

    val virtualFileManager = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
    return workspace.sourceUrls.asSequence().withIndex().any { (index, url) ->
        if (index and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
        virtualFileManager.findFileByUrl(url)
            ?.let { file -> moduleBaseContentScopeContains(module, file) } == true
    }
}

private const val CANCELLATION_CHECK_MASK: Int = 0x3f
private const val COMPLETION_FILE_PREFIX: String = "EternalScriptShared_"

@OptIn(KaPlatformInterface::class)
internal fun moduleBaseContentScopeContains(module: KaModule, file: VirtualFile): Boolean =
    module.baseContentScope.contains(file)

@OptIn(KaSpiExtensionPoint::class)
private class Idea262ResolveExtension(
    private val workspaces: List<EternalScriptWorkspace>
) : KaResolveExtension() {
    override fun getKtFiles(): List<KaResolveExtensionFile> =
        workspaces.mapNotNull { workspace ->
            workspace.generatedFiles.firstOrNull { generated ->
                generated.fileName.startsWith(COMPLETION_FILE_PREFIX)
            }?.let(::Idea262ResolveExtensionFile)
        }

    override fun getContainedPackages(): Set<FqName> =
        workspaces.mapTo(linkedSetOf()) { workspace -> workspace.packageName }

    override fun getShadowedScope(): GlobalSearchScope = GlobalSearchScope.EMPTY_SCOPE

    override fun dispose() = Unit
}

@OptIn(KaSpiExtensionPoint::class)
private class Idea262ResolveExtensionFile(
    private val generated: EternalScriptGeneratedFile
) : KaResolveExtensionFile() {
    override fun getFileName(): String = generated.fileName
    override fun getFilePackageName(): FqName = generated.packageName
    override fun getTopLevelClassifierNames(): Set<Name> = generated.topLevelClassifierNames
    override fun getTopLevelCallableNames(): Set<Name> = generated.topLevelCallableNames

    override fun buildFileText(): String = generated.text

    override fun createNavigationTargetsProvider(): KaResolveExtensionNavigationTargetsProvider =
        object : KaResolveExtensionNavigationTargetsProvider() {
            override fun KaSession.getNavigationTargets(element: KtElement): Collection<PsiElement> =
                navigationTarget(element)?.let(::listOf).orEmpty()
        }

    private fun navigationTarget(element: KtElement): PsiElement? {
        val offset = element.textOffset
        val name = (element as? org.jetbrains.kotlin.psi.KtNamedDeclaration
            ?: element.getParentOfType<org.jetbrains.kotlin.psi.KtNamedDeclaration>(strict = false))?.name
        return generated.mappings
            .filter { mapping -> name == null || mapping.sourcePointer.element?.name == name }
            .firstOrNull { mapping -> mapping.range.containsOffset(offset) }
            ?.sourcePointer?.element
            ?: generated.mappings
                .filter { mapping -> name != null && mapping.sourcePointer.element?.name == name }
                .singleOrNull()?.sourcePointer?.element
    }
}
