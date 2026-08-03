package eternalScript.core.script.project

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.security.MessageDigest

internal data class ScriptProjectModuleFile(
    val name: String,
    val text: String,
    val facadeClassName: String?,
    private val lineOrigins: List<ScriptProjectModuleLineOrigin?>
) {
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
 * initialization semantics. No synthetic entry bootstrap is generated.
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
            val digest = MessageDigest.getInstance("SHA-256")
            projectFiles.forEach { file ->
                digest.update(file.name.toByteArray())
                digest.update(0.toByte())
                digest.update(file.text.toByteArray())
                digest.update(0.toByte())
            }
            return ScriptProjectModule(projectFiles, digest.digest().toHexString())
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

    }
}

private fun ScriptProjectFile.toModuleFile(
    factory: KtPsiFactory
): ScriptProjectModuleFile {
    val parsed = factory.createFile(name.sourceBaseName(), text)
    val fileClasses = parsed.fileClasses()
    val output = MappedSourceBuilder()
    output.appendOriginal(text, name, firstLine = 1, firstColumn = 1)

    return output.build(
        name = name,
        facadeClassName = fileClasses
    )
}

private fun KtFile.fileClasses(): String? {
    val hasTopLevelJvmMember = declarations.any { declaration ->
        declaration is KtNamedFunction || declaration is KtProperty
    }
    return if (hasTopLevelJvmMember) {
        JvmFileClassUtil.getFileClassInfoNoResolve(this).fileClassFqName.asString()
    } else {
        null
    }
}

private class MappedSourceBuilder {
    private val text = StringBuilder()
    private val origins = mutableListOf<ScriptProjectModuleLineOrigin?>(null)
    private var generatedColumn = 1

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

    fun build(
        name: String,
        facadeClassName: String?
    ) = ScriptProjectModuleFile(
        name = name,
        text = text.toString(),
        facadeClassName = facadeClassName,
        lineOrigins = origins.toList()
    )
}

private fun String.normalizedSourceName(): String = replace('\\', '/')

private fun String.sourceBaseName(): String =
    normalizedSourceName().substringAfterLast('/')
