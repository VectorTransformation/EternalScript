package eternalscript.intellij.model

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPsiElementPointer
import eternalscript.ide.protocol.IdeEnvironment
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import java.nio.file.Path

internal data class EternalScriptSourceLocation(
    val fileUrl: String,
    val offset: Int
)

internal sealed interface EternalScriptConflict {
    data class DuplicateDeclaration(
        val name: String,
        val paths: List<String>
    ) : EternalScriptConflict

    data class ConflictingImport(
        val name: String,
        val imports: List<String>
    ) : EternalScriptConflict
}

internal data class EternalScriptGeneratedMapping(
    val range: TextRange,
    val source: EternalScriptSourceLocation,
    val sourcePointer: SmartPsiElementPointer<KtNamedDeclaration>
)

internal data class EternalScriptGeneratedFile(
    val fileName: String,
    val packageName: FqName,
    val text: String,
    val textDigest: String,
    val topLevelClassifierNames: Set<Name>,
    val topLevelCallableNames: Set<Name>,
    val mappings: List<EternalScriptGeneratedMapping>
)

internal data class EternalScriptImportEntry(
    val name: String?,
    val importPath: String,
    val source: EternalScriptSourceLocation,
    val sourcePointer: SmartPsiElementPointer<KtImportDirective>
)

internal data class EternalScriptRenderedDeclaration(
    val name: String,
    val text: String,
    val mappings: List<EternalScriptGeneratedMapping>,
    val symbolId: String = "",
    val signature: String = name,
    val kind: EternalScriptDeclarationKind = EternalScriptDeclarationKind.FUNCTION,
    val exposedType: String? = null,
    val receiverType: String? = null,
    val parameterTypes: List<String> = emptyList()
)

internal enum class EternalScriptDeclarationKind {
    PROPERTY,
    FUNCTION,
    CLASSIFIER
}

internal data class EternalScriptReferenceCandidate(
    val name: String,
    val sourceUrl: String,
    val pointer: SmartPsiElementPointer<KtNameReferenceExpression>
)

internal data class EternalScriptFileAbi(
    val sourceUrl: String,
    val contentStamp: Long,
    val contentDigest: String,
    val abiDigest: String,
    val imports: List<String>,
    val callables: List<EternalScriptRenderedDeclaration>,
    val classifiers: List<EternalScriptRenderedDeclaration>,
    val classifierNames: Set<Name>,
    val declaredNames: Set<String>,
    val referencedNames: Set<String>,
    val stable: Boolean,
    val referenceCandidates: List<EternalScriptReferenceCandidate> = emptyList(),
    val importEntries: List<EternalScriptImportEntry> = emptyList(),
    val retryable: Boolean = !stable
)

internal data class EternalScriptWorkspace(
    val id: String,
    val manifest: Path,
    val manifestDigest: String,
    val scriptRoot: Path,
    val environment: IdeEnvironment,
    val packageName: FqName,
    val receiverName: String,
    val sourceUrls: Set<String>,
    val activeSourceUrls: Set<String>,
    val fileAbis: Map<String, EternalScriptFileAbi>,
    val conflicts: Map<EternalScriptSourceLocation, EternalScriptConflict>,
    val pendingInvalidatedNames: Set<String>,
    val generatedFiles: List<EternalScriptGeneratedFile>,
    val configurationFingerprint: String,
    val digest: String
) {
    val receiverFqName: String = "${packageName.asString()}.$receiverName"
    val generatedText: String
        get() = generatedFiles.joinToString("\n", transform = EternalScriptGeneratedFile::text)

    fun contains(file: VirtualFile): Boolean = file.url in sourceUrls || runCatching {
        val path = file.toNioPath().toAbsolutePath().normalize()
        path.startsWith(scriptRoot) && EternalScriptIncrementalPlanner.isVisibleToIde(scriptRoot, path)
    }.getOrDefault(false)

    fun isActive(file: VirtualFile): Boolean = file.url in activeSourceUrls

    fun generatedFile(fileName: String): EternalScriptGeneratedFile? =
        generatedFiles.firstOrNull { generated -> generated.fileName == fileName }
}

internal data class EternalScriptProjectSnapshot(
    val workspaces: List<EternalScriptWorkspace>,
    val problems: List<EternalScriptEnvironmentProblem>,
    val digest: String,
    val version: Long = 0,
    val metrics: EternalScriptIdeMetrics = EternalScriptIdeMetrics.EMPTY
) {
    fun workspaceFor(file: VirtualFile): EternalScriptWorkspace? = workspaces
        .filter { workspace -> workspace.contains(file) }
        .maxByOrNull { workspace -> workspace.scriptRoot.nameCount }

    companion object {
        val EMPTY: EternalScriptProjectSnapshot = EternalScriptProjectSnapshot(emptyList(), emptyList(), "")
    }
}

internal data class EternalScriptIdeMetrics(
    val workspaceScans: Long,
    val changedFiles: Long,
    val abiAnalyses: Long,
    val configurationReloads: Long,
    val cancellations: Long,
    val lastAnalysisMillis: Long
) {
    companion object {
        val EMPTY: EternalScriptIdeMetrics = EternalScriptIdeMetrics(0, 0, 0, 0, 0, 0)
    }
}

internal sealed interface EternalScriptEnvironmentProblem {
    val manifest: Path?

    data class Missing(override val manifest: Path? = null) : EternalScriptEnvironmentProblem

    data class Invalid(override val manifest: Path, val reason: String) : EternalScriptEnvironmentProblem

    data class Incompatible(
        override val manifest: Path,
        val actual: Int,
        val expected: Int
    ) : EternalScriptEnvironmentProblem

    data class Untrusted(override val manifest: Path) : EternalScriptEnvironmentProblem

    data class UnsafeScriptRoot(
        override val manifest: Path,
        val root: String
    ) : EternalScriptEnvironmentProblem

    data class MissingClasspath(
        override val manifest: Path,
        val paths: List<Path>
    ) : EternalScriptEnvironmentProblem

    data class IncompatibleKotlin(
        override val manifest: Path,
        val actual: String,
        val expected: String
    ) : EternalScriptEnvironmentProblem

    data class AnalysisUnstable(
        override val manifest: Path,
        val sourceUrls: Set<String>
    ) : EternalScriptEnvironmentProblem
}
