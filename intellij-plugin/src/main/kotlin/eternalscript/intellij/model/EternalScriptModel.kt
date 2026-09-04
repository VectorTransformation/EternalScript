package eternalscript.intellij.model

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPsiElementPointer
import eternalscript.ide.protocol.IdeEnvironment
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Path

internal data class EternalScriptSourceLocation(
    val fileUrl: String,
    val offset: Int
)

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
    val fileAbis: Map<String, EternalScriptFileAbi>,
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

    fun generatedFile(fileName: String): EternalScriptGeneratedFile? =
        generatedFiles.firstOrNull { generated -> generated.fileName == fileName }
}

internal data class EternalScriptProjectSnapshot(
    val workspaces: List<EternalScriptWorkspace>,
    val digest: String,
    val version: Long = 0
) {
    fun workspaceFor(file: VirtualFile): EternalScriptWorkspace? = workspaces
        .filter { workspace -> workspace.contains(file) }
        .maxByOrNull { workspace -> workspace.scriptRoot.nameCount }

    companion object {
        val EMPTY: EternalScriptProjectSnapshot = EternalScriptProjectSnapshot(emptyList(), "")
    }
}
