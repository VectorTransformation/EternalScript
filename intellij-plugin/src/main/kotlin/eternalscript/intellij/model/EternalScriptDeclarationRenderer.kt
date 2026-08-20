package eternalscript.intellij.model

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.SmartPointerManager
import eternalscript.intellij.resolve.Idea262Facade
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.security.MessageDigest

internal object EternalScriptDeclarationRenderer {
    fun renderFile(
        file: KtFile,
        previous: EternalScriptFileAbi?,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>> = emptyMap()
    ): EternalScriptFileAbi {
        ProgressManager.checkCanceled()
        val sourceUrl = requireNotNull(file.virtualFile).url
        val contentStamp = file.modificationStamp
        val fileText = file.text
        ProgressManager.checkCanceled()
        val contentDigest = digest(fileText)
        val scan = scanFile(file)
        val referencedNames = linkedSetOf<String>()
        val referenceCandidates = ArrayList<EternalScriptReferenceCandidate>(scan.references.size)
        scan.references.forEachIndexed { index, reference ->
            if (index and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
            val name = reference.getReferencedName()
            referencedNames += name
            referenceCandidates += EternalScriptReferenceCandidate(
                name,
                sourceUrl,
                SmartPointerManager.createPointer(reference)
            )
        }
        if (scan.hasSyntaxErrors) {
            return previous?.copy(
                contentDigest = contentDigest,
                contentStamp = contentStamp,
                referencedNames = referencedNames,
                stable = false,
                referenceCandidates = referenceCandidates,
                retryable = false
            ) ?: emptyAbi(sourceUrl, contentStamp, contentDigest, referencedNames, referenceCandidates)
        }

        val importDirectives = file.importDirectives
        val imports = importDirectives.asSequence()
            .map(::importText)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()
        val importEntries = ArrayList<EternalScriptImportEntry>(importDirectives.size)
        importDirectives.forEachIndexed { index, directive ->
            if (index and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
            val path = importText(directive)
            importEntries += EternalScriptImportEntry(
                EternalScriptImportPlanner.importedName(path),
                path,
                EternalScriptSourceLocation(sourceUrl, directive.textOffset),
                SmartPointerManager.createPointer(directive)
            )
        }

        val callables = mutableListOf<EternalScriptRenderedDeclaration>()
        val classifiers = mutableListOf<EternalScriptRenderedDeclaration>()
        val classifierNames = linkedSetOf<Name>()
        val declaredNames = linkedSetOf<String>()
        var stable = true
        declarations(file).asSequence().filter(::isShared).forEachIndexed { index, declaration ->
            if (index and DECLARATION_CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
            when (declaration) {
                is KtCallableDeclaration -> {
                    val name = declaration.name ?: return@forEachIndexed
                    val rendered = EternalScriptDeclarationStubRenderer.callable(declaration, availableDeclarations)
                    val previousRendered = previousDeclaration(previous?.callables.orEmpty(), declaration)
                    val text = if (rendered == null) {
                        stable = false
                        previousRendered?.text ?: return@forEachIndexed
                    } else {
                        declaration.docComment?.text?.let { documentation -> "$documentation\n${rendered.text}" }
                            ?: rendered.text
                    }
                    val signatureSource = rendered ?: previousRendered?.let { prior ->
                        EternalScriptRenderedCallable(
                            prior.text.substringAfterLast("*/\n", prior.text),
                            prior.exposedType,
                            prior.kind,
                            prior.receiverType,
                            prior.parameterTypes
                        )
                    }
                    callables += fragment(
                        declaration,
                        name,
                        text,
                        declarationSignature(declaration, signatureSource),
                        signatureSource
                    )
                    declaredNames += name
                }
                is KtClassOrObject, is KtTypeAlias -> {
                    val named = declaration as KtNamedDeclaration
                    val name = named.name ?: return@forEachIndexed
                    val rendered = classifierFragment(named, name, availableDeclarations)
                    val fragment = if (rendered == null) {
                        stable = false
                        previousDeclaration(previous?.classifiers.orEmpty(), named) ?: return@forEachIndexed
                    } else {
                        rendered
                    }
                    classifiers += fragment
                    classifierNames += Name.identifier(name)
                    declaredNames += name
                }
            }
        }
        ProgressManager.checkCanceled()
        val abiDigest = digest(buildString {
            imports.forEach { value -> append("import ").appendLine(value) }
            callables.forEach { declaration -> appendLine(declaration.text) }
            classifiers.forEach { declaration -> appendLine(declaration.text) }
        })
        return EternalScriptFileAbi(
            sourceUrl,
            contentStamp,
            contentDigest,
            abiDigest,
            imports,
            callables,
            classifiers,
            classifierNames,
            declaredNames,
            referencedNames,
            stable,
            referenceCandidates,
            importEntries
        )
    }

    fun render(
        workspaceId: String,
        packageName: String,
        receiverName: String,
        files: List<EternalScriptFileAbi>
    ): List<EternalScriptGeneratedFile> {
        ProgressManager.checkCanceled()
        val packageFqName = FqName(packageName)
        val completionFile = renderCompletionFile(
            workspaceId,
            packageFqName,
            receiverName,
            files.sortedBy(EternalScriptFileAbi::sourceUrl)
        )
        return listOf(completionFile)
    }

    /**
     * IDEA 262 requires one resolve-extension file for reliable script analysis and completion.
     * Declaration stubs contain K2-rendered qualified types, so this sole synthetic file stays
     * import-free. Its mappings provide navigation without publishing duplicate declaration files.
     */
    private fun renderCompletionFile(
        workspaceId: String,
        packageName: FqName,
        receiverName: String,
        files: List<EternalScriptFileAbi>
    ): EternalScriptGeneratedFile {
        val output = StringBuilder()
        val mappings = mutableListOf<EternalScriptGeneratedMapping>()
        val classifierNames = linkedSetOf(Name.identifier(receiverName))
        val callableNames = linkedSetOf<Name>()
        output.append("package ").append(packageName.asString()).append("\n\n")
        output.append("abstract class ").append(receiverName)
            .append(" : eternalscript.api.script.Script()\n")

        files.forEach { file ->
            classifierNames += file.classifierNames
            file.callables.forEach { declaration ->
                output.append('\n')
                val start = output.length
                val text = if (declaration.kind == EternalScriptDeclarationKind.FUNCTION) {
                    topLevelFunctionText(declaration.text)
                } else {
                    declaration.text
                }
                output.append(text).append('\n')
                val end = output.length
                mappings += declaration.mappings.first().copy(range = TextRange(start, end))
                callableNames += Name.identifier(declaration.name)
            }
            file.classifiers.forEach { declaration ->
                output.append('\n')
                val start = output.length
                output.append(declaration.text).append('\n')
                val end = output.length
                declaration.mappings.forEach { mapping ->
                    mappings += mapping.copy(
                        range = TextRange(
                            start + mapping.range.startOffset,
                            (start + mapping.range.endOffset).coerceAtMost(end)
                        )
                    )
                }
            }
        }

        val text = output.toString()
        return EternalScriptGeneratedFile(
            "EternalScriptShared_${workspaceId}.kt",
            packageName,
            text,
            digest(text),
            classifierNames,
            callableNames,
            mappings.sortedBy { mapping -> mapping.range.length }
        )
    }

    private fun topLevelFunctionText(text: String): String {
        val functionKeyword = text.lastIndexOf("fun ")
        if (functionKeyword < 0) return text
        val declarationLineStart = text.lastIndexOf('\n', functionKeyword).let { index ->
            if (index < 0) 0 else index + 1
        }
        val prefix = text.substring(0, declarationLineStart)
        val modifiers = text.substring(declarationLineStart, functionKeyword)
            .replace(TOP_LEVEL_FUNCTION_MODIFIERS, "")
        val declaration = prefix + modifiers + text.substring(functionKeyword)
        return if (SYNTHETIC_FUNCTION_BODY_AT_END.containsMatchIn(declaration)) {
            declaration
        } else {
            "$declaration = kotlin.error(\"EternalScript IDE declaration\")"
        }
    }

    private fun fragment(
        declaration: KtNamedDeclaration,
        name: String,
        text: String,
        signature: String,
        rendered: EternalScriptRenderedCallable?
    ): EternalScriptRenderedDeclaration {
        val mapping = EternalScriptGeneratedMapping(
            TextRange(0, text.length),
            sourceLocation(declaration),
            SmartPointerManager.createPointer(declaration)
        )
        val symbolId = digest("${sourceLocation(declaration).fileUrl}\u0000${declaration.textOffset}\u0000$signature")
        return EternalScriptRenderedDeclaration(
            name,
            text,
            listOf(mapping),
            symbolId,
            signature,
            rendered?.kind ?: EternalScriptDeclarationKind.FUNCTION,
            rendered?.exposedType,
            rendered?.receiverType,
            rendered?.parameterTypes.orEmpty()
        )
    }

    private fun classifierFragment(
        declaration: KtNamedDeclaration,
        name: String,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): EternalScriptRenderedDeclaration? {
        val sourceUrl = requireNotNull(declaration.containingKtFile.virtualFile).url
        val rendered = EternalScriptDeclarationStubRenderer.classifier(
            sourceUrl,
            declaration,
            availableDeclarations
        ) ?: return null
        val signature = "classifier:$name"
        val symbolId = digest("$sourceUrl\u0000${declaration.textOffset}\u0000$signature")
        return EternalScriptRenderedDeclaration(
            name,
            rendered.text,
            rendered.mappings,
            symbolId,
            signature,
            EternalScriptDeclarationKind.CLASSIFIER,
            name
        )
    }

    private fun previousDeclaration(
        previous: List<EternalScriptRenderedDeclaration>,
        declaration: KtNamedDeclaration
    ): EternalScriptRenderedDeclaration? = previous.firstOrNull { candidate ->
        candidate.name == declaration.name && candidate.mappings.firstOrNull()?.sourcePointer?.element === declaration
    } ?: previous.firstOrNull { candidate ->
        val source = candidate.mappings.firstOrNull()?.source
        candidate.name == declaration.name && source?.fileUrl == declaration.containingKtFile.virtualFile?.url &&
            source?.offset == declaration.textOffset
    }

    private fun sourceLocation(declaration: KtNamedDeclaration): EternalScriptSourceLocation =
        EternalScriptSourceLocation(requireNotNull(declaration.containingKtFile.virtualFile).url, declaration.textOffset)

    private data class FileScan(
        val references: List<KtNameReferenceExpression>,
        val hasSyntaxErrors: Boolean
    )

    private fun scanFile(file: KtFile): FileScan {
        val references = ArrayList<KtNameReferenceExpression>()
        var hasSyntaxErrors = false
        var visited = 0
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (visited++ and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
                when (element) {
                    is KtNameReferenceExpression -> references += element
                    is PsiErrorElement -> hasSyntaxErrors = true
                }
                super.visitElement(element)
            }
        })
        ProgressManager.checkCanceled()
        return FileScan(references, hasSyntaxErrors)
    }

    private fun declarations(file: KtFile): List<KtDeclaration> = file.script?.declarations ?: file.declarations

    private fun importText(directive: KtImportDirective): String =
        directive.text.removePrefix("import").trim()

    private fun isShared(declaration: KtDeclaration): Boolean = !declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)

    private fun emptyAbi(
        sourceUrl: String,
        contentStamp: Long,
        contentDigest: String,
        referencedNames: Set<String>,
        referenceCandidates: List<EternalScriptReferenceCandidate>
    ): EternalScriptFileAbi = EternalScriptFileAbi(
        sourceUrl = sourceUrl,
        contentStamp = contentStamp,
        contentDigest = contentDigest,
        abiDigest = digest(""),
        imports = emptyList(),
        callables = emptyList(),
        classifiers = emptyList(),
        classifierNames = emptySet(),
        declaredNames = emptySet(),
        referencedNames = referencedNames,
        stable = false,
        referenceCandidates = referenceCandidates,
        retryable = false
    )

    private fun declarationSignature(
        declaration: KtCallableDeclaration,
        rendered: EternalScriptRenderedCallable?
    ): String {
        val semantic = if (
            declaration.containingKtFile.script == null ||
            Idea262Facade.scriptDependenciesReady(declaration.containingKtFile)
        ) {
            Idea262Facade.canonicalCallableSignature(declaration)
        } else {
            null
        }
        val typeParameterCount = semantic?.typeParameterCount ?: declaration.typeParameters.size
        val receiverType = semantic?.receiverType ?: rendered?.receiverType.orEmpty()
        val parameterTypes = semantic?.parameterTypes ?: rendered?.parameterTypes.orEmpty()
        return when (declaration) {
            is KtProperty -> buildString {
                append("property:").append(declaration.name)
                append('<').append(typeParameterCount).append('>')
                append(':').append(receiverType)
            }
            is KtNamedFunction -> buildString {
                append("function:").append(declaration.name)
                append('<').append(typeParameterCount).append('>')
                append(':').append(receiverType)
                parameterTypes.forEach { parameterType -> append(':').append(parameterType) }
            }
            else -> "callable:${declaration.name}"
        }
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val CANCELLATION_CHECK_MASK: Int = 0x7f
    private const val DECLARATION_CANCELLATION_CHECK_MASK: Int = 0x1f

    private val TOP_LEVEL_FUNCTION_MODIFIERS =
        Regex("\\b(?:abstract|expect|external|final|open|override|protected)\\s+")
    private val SYNTHETIC_FUNCTION_BODY_AT_END =
        Regex("=\\s*(?:kotlin\\.)?error\\(\"EternalScript IDE declaration\"\\)\\s*$")
}
