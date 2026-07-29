package eternalScript.core.script.project

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.security.MessageDigest

internal const val GENERATED_PROJECT_PACKAGE = "eternalScript.generated.project"
internal const val GENERATED_BOOTSTRAP_CLASS =
    "$GENERATED_PROJECT_PACKAGE.EternalScriptProjectBootstrap"

internal data class ScriptProjectModuleFile(
    val name: String,
    val text: String,
    val facadeClassName: String?,
    val exportClassName: String?,
    val entryPoint: ScriptProjectModuleEntryPoint?,
    private val lineOrigins: List<ScriptProjectModuleLineOrigin?>
) {
    val loaderFunction: String?
        get() = entryPoint?.callableFqName

    fun position(line: Int, column: Int): ScriptProjectPosition? {
        if (line < 1 || column < 1) return null
        val origin = lineOrigins.getOrNull(line - 1) ?: return null
        return ScriptProjectPosition(
            origin.sourceName,
            origin.line,
            (column + origin.columnOffset).coerceAtLeast(1)
        )
    }
}

internal data class ScriptProjectModuleEntryPoint(
    val sourcePosition: ScriptProjectPosition,
    val sourceOffset: Int,
    val callableFqName: String,
    val importAlias: String
)

internal data class ScriptProjectModuleLineOrigin(
    val sourceName: String,
    val line: Int,
    val columnOffset: Int
)

/**
 * Ordinary Kotlin/JVM module generated from the user project.
 *
 * User files are compiled with their original paths and text, preserving
 * normal Kotlin package, import, visibility, file-annotation, and
 * initialization semantics. Only the deterministic lifecycle bootstrap is
 * generated.
 */
internal class ScriptProjectModule private constructor(
    val files: List<ScriptProjectModuleFile>,
    val fingerprint: String
) {
    private val sourceByName = files.associateBy { file ->
        file.name.normalizedSourceName().lowercase()
    }
    private val sourceBySuffix = files.sortedByDescending { file ->
        file.name.normalizedSourceName().length
    }
    private val sourceByFacadeClass = files.mapNotNull { file ->
        file.facadeClassName?.let { className -> className to file }
    }.toMap()
    private val uniqueSourceByBaseName = files
        .groupBy { file -> file.name.sourceBaseName().lowercase() }
        .filterValues { matches -> matches.size == 1 }
        .mapValues { (_, matches) -> matches.single() }

    fun ownsSource(sourceName: String): Boolean = sourceFile(sourceName) != null

    fun position(sourceName: String, line: Int, column: Int): ScriptProjectPosition? =
        sourceFile(sourceName)?.position(line, column)

    fun position(
        className: String,
        sourceName: String,
        line: Int,
        column: Int
    ): ScriptProjectPosition? =
        sourceByFacadeClass[className.substringBefore('$')]?.position(line, column)
            ?: position(sourceName, line, column)

    private fun sourceFile(sourceName: String): ScriptProjectModuleFile? {
        val normalized = sourceName.normalizedSourceName()
        val lowercase = normalized.lowercase()
        return sourceByName[lowercase]
            ?: sourceBySuffix.firstOrNull { file ->
                lowercase.endsWith("/${file.name.normalizedSourceName().lowercase()}")
            }
            ?: uniqueSourceByBaseName[normalized.sourceBaseName().lowercase()]
    }

    companion object {
        fun create(project: ScriptProjectSource): ScriptProjectModule {
            val projectFiles = parseProjectFiles(project.files)
            val runtime = runtimeFile(projectFiles)
            val files = projectFiles + runtime
            val digest = MessageDigest.getInstance("SHA-256")
            files.forEach { file ->
                digest.update(file.name.toByteArray())
                digest.update(0.toByte())
                digest.update(file.text.toByteArray())
                digest.update(0.toByte())
            }
            return ScriptProjectModule(files, digest.digest().toHexString())
        }

        @OptIn(
            org.jetbrains.kotlin.K1Deprecation::class,
            CompilerConfiguration.Internals::class
        )
        private fun parseProjectFiles(
            files: List<ScriptProjectFile>
        ): List<ScriptProjectModuleFile> {
            val disposable = Disposer.newDisposable("EternalScript project module parser")
            return try {
                val configuration = CompilerConfiguration().apply {
                    put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                }
                val environment = KotlinCoreEnvironment.createForProduction(
                    disposable,
                    configuration,
                    EnvironmentConfigFiles.JVM_CONFIG_FILES
                )
                val factory = KtPsiFactory(environment.project, false)
                files.map { file ->
                    file.toModuleFile(factory)
                }
            } finally {
                Disposer.dispose(disposable)
            }
        }

        private fun runtimeFile(
            projectFiles: List<ScriptProjectModuleFile>
        ): ScriptProjectModuleFile {
            val entries = projectFiles.mapNotNull(ScriptProjectModuleFile::entryPoint)
                .sortedWith(
                    compareBy<ScriptProjectModuleEntryPoint> {
                        it.sourcePosition.sourceName.lowercase()
                    }
                        .thenBy { entry -> entry.sourcePosition.sourceName }
                        .thenBy(ScriptProjectModuleEntryPoint::sourceOffset)
                )
            val output = MappedSourceBuilder()
            output.appendGenerated("package $GENERATED_PROJECT_PACKAGE\n\n")
            output.appendGenerated("import eternalScript.core.script.Script\n")
            entries.forEach { entry ->
                output.appendGenerated(
                    "import ${entry.callableFqName} as ${entry.importAlias}\n"
                )
            }
            output.appendGenerated("\n")
            output.appendGenerated("private class EternalScriptProjectInstance : Script()\n\n")
            output.appendGenerated("object EternalScriptProjectBootstrap {\n")
            output.appendGenerated("    @JvmStatic\n")
            output.appendGenerated("    fun create(): Script {\n")
            output.appendGenerated("        val script = EternalScriptProjectInstance()\n")
            output.appendGenerated("        return try {\n")
            entries.forEach { entry ->
                val resultName = "eternalScriptEntryResult_" +
                    entry.sourcePosition.sourceName.sha256().take(GENERATED_NAME_HASH_LENGTH)
                output.appendGenerated("            val $resultName: kotlin.Unit = script.")
                output.appendMapped("${entry.importAlias}()", entry.sourcePosition)
                output.appendGenerated("\n")
            }
            output.appendGenerated("            script\n")
            output.appendGenerated("        } catch (exception: Throwable) {\n")
            output.appendGenerated(
                "            throw eternalScript.core.script.project." +
                    "ScriptProjectInitializationException(\n"
            )
            output.appendGenerated("                script,\n")
            output.appendGenerated("                exception\n")
            output.appendGenerated("            )\n")
            output.appendGenerated("        }\n")
            output.appendGenerated("    }\n")
            output.appendGenerated("}\n")
            return output.build(
                name = GENERATED_RUNTIME_FILE,
                facadeClassName = null,
                exportClassName = null,
                entryPoint = null
            )
        }
    }
}

private fun ScriptProjectFile.toModuleFile(
    factory: KtPsiFactory
): ScriptProjectModuleFile {
    val parsed = factory.createFile(name.sourceBaseName(), text)
    val entryPoint = parsed.entryPoint(this)
    val fileClasses = parsed.fileClasses()
    val output = MappedSourceBuilder()
    output.appendOriginal(text, name, firstLine = 1, firstColumn = 1)

    return output.build(
        name = name,
        facadeClassName = fileClasses?.implementation,
        exportClassName = fileClasses?.export,
        entryPoint = entryPoint
    )
}

private fun KtFile.entryPoint(
    source: ScriptProjectFile
): ScriptProjectModuleEntryPoint? {
    val entryNames = referenceNames(ENTRY_ANNOTATION_FQ_NAME)
    val annotatedFunctions = PsiTreeUtil.collectElementsOfType(
        this,
        KtNamedFunction::class.java
    ).filter { function ->
        function.annotationEntries.any { annotation ->
            annotation.typeReference?.text?.normalizedReference() in entryNames
        }
    }
    if (annotatedFunctions.isEmpty()) return null

    val topLevelFunctions = declarations.filterIsInstance<KtNamedFunction>().toSet()
    annotatedFunctions.firstOrNull { function -> function !in topLevelFunctions }?.let {
        throw compositionError(
            source.name,
            it,
            "@EternalScriptEntry is only supported on a top-level function."
        )
    }
    if (annotatedFunctions.size > 1) {
        throw compositionError(
            source.name,
            annotatedFunctions[1],
            "Each Kotlin source file may declare at most one @EternalScriptEntry function."
        )
    }

    val function = annotatedFunctions.single()
    val annotations = function.annotationEntries.filter { annotation ->
        annotation.typeReference?.text?.normalizedReference() in entryNames
    }
    if (annotations.size > 1) {
        throw compositionError(
            source.name,
            function,
            "@EternalScriptEntry must not be repeated on the same function."
        )
    }
    if (annotations.single().valueArguments.isNotEmpty()) {
        throw compositionError(
            source.name,
            annotations.single(),
            "@EternalScriptEntry does not accept arguments."
        )
    }
    if (
        function.hasModifier(KtTokens.PRIVATE_KEYWORD) ||
        function.hasModifier(KtTokens.PROTECTED_KEYWORD)
    ) {
        throw compositionError(
            source.name,
            function,
            "An EternalScript entry must be public or internal."
        )
    }
    if (function.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
        throw compositionError(source.name, function, "An EternalScript entry must not be suspend.")
    }
    if (function.typeParameters.isNotEmpty()) {
        throw compositionError(
            source.name,
            function,
            "An EternalScript entry must not declare type parameters."
        )
    }
    if (function.valueParameters.isNotEmpty()) {
        throw compositionError(
            source.name,
            function,
            "An EternalScript entry must not declare value parameters."
        )
    }
    val receiver = function.receiverTypeReference
        ?: throw compositionError(
            source.name,
            function,
            "An EternalScript entry must be an extension function on $SCRIPT_FQ_NAME."
        )
    if (receiver.text.normalizedReference() !in referenceNames(SCRIPT_FQ_NAME)) {
        throw compositionError(
            source.name,
            receiver,
            "An EternalScript entry receiver must be exactly $SCRIPT_FQ_NAME."
        )
    }
    function.typeReference?.let { returnType ->
        if (returnType.text.normalizedReference() !in UNIT_TYPE_NAMES) {
            throw compositionError(
                source.name,
                returnType,
                "An EternalScript entry must return kotlin.Unit."
            )
        }
    }

    val nameIdentifier = function.nameIdentifier
        ?: throw compositionError(
            source.name,
            function,
            "An EternalScript entry must have a name."
        )
    val sourcePosition = source.sourcePosition(nameIdentifier.textOffset)
    val sourceHash = source.name.sha256().take(GENERATED_NAME_HASH_LENGTH)
    return ScriptProjectModuleEntryPoint(
        sourcePosition = sourcePosition,
        sourceOffset = nameIdentifier.textOffset,
        callableFqName = packageFqName.asString().qualify(nameIdentifier.text),
        importAlias = "eternalScriptProjectEntry_$sourceHash"
    )
}

private fun KtFile.referenceNames(fqName: String): Set<String> {
    val packageName = fqName.substringBeforeLast('.')
    val shortName = fqName.substringAfterLast('.')
    return buildSet {
        add(fqName.normalizedReference())
        if (packageFqName.asString() == packageName) {
            add(shortName.normalizedReference())
        }
        importDirectives.forEach { directive ->
            val imported = directive.importedFqName?.asString() ?: return@forEach
            if (!directive.isAllUnder && imported == fqName) {
                add((directive.aliasName ?: shortName).normalizedReference())
            }
        }
    }
}

private data class JvmFileClasses(
    val implementation: String,
    val export: String
)

private fun KtFile.fileClasses(): JvmFileClasses? {
    val hasTopLevelJvmMember = declarations.any { declaration ->
        declaration is KtNamedFunction || declaration is KtProperty
    }
    return if (hasTopLevelJvmMember) {
        val info = JvmFileClassUtil.getFileClassInfoNoResolve(this)
        JvmFileClasses(
            implementation = info.fileClassFqName.asString(),
            export = info.facadeClassFqName.asString()
        )
    } else {
        null
    }
}

private fun ScriptProjectFile.sourcePosition(offset: Int): ScriptProjectPosition {
    val lineStarts = text.lineStarts()
    return ScriptProjectPosition(
        sourceName = name,
        line = lineStarts.lineNumber(offset),
        column = lineStarts.columnNumber(offset)
    )
}

private fun KtFile.compositionError(
    sourceName: String,
    element: org.jetbrains.kotlin.com.intellij.psi.PsiElement,
    message: String
): ScriptProjectCompositionException {
    val lineStarts = text.lineStarts()
    val line = lineStarts.lineNumber(element.textOffset)
    val column = lineStarts.columnNumber(element.textOffset)
    return ScriptProjectCompositionException("$sourceName:$line:$column $message")
}

private class MappedSourceBuilder {
    private val text = StringBuilder()
    private val origins = mutableListOf<ScriptProjectModuleLineOrigin?>(null)
    private var generatedColumn = 1

    fun appendGenerated(value: String) {
        value.forEach { character ->
            text.append(character)
            if (character == '\n') {
                origins += null
                generatedColumn = 1
            } else {
                generatedColumn += 1
            }
        }
    }

    fun appendOriginal(
        value: String,
        sourceName: String,
        firstLine: Int,
        firstColumn: Int
    ) {
        var sourceLine = firstLine
        var sourceColumn = firstColumn
        value.forEach { character ->
            if (origins.last() == null) {
                origins[origins.lastIndex] = ScriptProjectModuleLineOrigin(
                    sourceName = sourceName,
                    line = sourceLine,
                    columnOffset = sourceColumn - generatedColumn
                )
            }
            text.append(character)
            if (character == '\n') {
                origins += null
                sourceLine += 1
                sourceColumn = 1
                generatedColumn = 1
            } else {
                sourceColumn += 1
                generatedColumn += 1
            }
        }
    }

    fun appendMapped(value: String, sourcePosition: ScriptProjectPosition) {
        appendOriginal(
            value,
            sourcePosition.sourceName,
            sourcePosition.line,
            sourcePosition.column
        )
    }

    fun build(
        name: String,
        facadeClassName: String?,
        exportClassName: String?,
        entryPoint: ScriptProjectModuleEntryPoint?
    ) = ScriptProjectModuleFile(
        name = name,
        text = text.toString(),
        facadeClassName = facadeClassName,
        exportClassName = exportClassName,
        entryPoint = entryPoint,
        lineOrigins = origins.toList()
    )
}

private fun String.lineStarts() = buildList {
    add(0)
    this@lineStarts.forEachIndexed { index, character ->
        if (character == '\n') add(index + 1)
    }
}

private fun List<Int>.lineNumber(offset: Int): Int = lineIndex(offset) + 1

private fun List<Int>.columnNumber(offset: Int): Int {
    val index = lineIndex(offset)
    return offset - this[index] + 1
}

private fun List<Int>.lineIndex(offset: Int): Int {
    val result = binarySearch(offset)
    val index = if (result >= 0) result else -result - 2
    return index.coerceAtLeast(0)
}

private fun String.normalizedReference(): String =
    filterNot(Char::isWhitespace).replace("`", "")

private fun String.normalizedSourceName(): String = replace('\\', '/')

private fun String.sourceBaseName(): String =
    normalizedSourceName().substringAfterLast('/')

private fun String.qualify(name: String): String =
    if (isBlank()) name else "$this.$name"

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .toHexString()

private const val ENTRY_ANNOTATION_FQ_NAME =
    "eternalScript.api.script.EternalScriptEntry"
private const val SCRIPT_FQ_NAME = "eternalScript.core.script.Script"
private const val GENERATED_RUNTIME_FILE =
    "-eternalscript-generated/EternalScriptProjectRuntime.kt"
private const val GENERATED_NAME_HASH_LENGTH = 16
private val UNIT_TYPE_NAMES = setOf("Unit", "kotlin.Unit")
