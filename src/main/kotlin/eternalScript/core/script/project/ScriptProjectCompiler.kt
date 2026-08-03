package eternalScript.core.script.project

import eternalScript.core.data.Resource
import eternalScript.core.script.classpath.ScriptClassIdentityConflictException
import eternalScript.core.script.classpath.ScriptPluginClasspathSnapshot
import eternalScript.core.script.definition.ScriptCompilationCache
import eternalScript.core.script.definition.ScriptRuntimeClasspath
import eternalScript.core.script.definition.scriptRuntimeClasspath
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import java.nio.file.Path
import kotlin.script.experimental.api.*
import kotlin.reflect.KClass

/**
 * Compiles one complete script project and produces an immutable compiled
 * artifact. It does not create class loaders or activate script instances.
 */
internal class ScriptProjectCompiler(
    private val cacheRoot: () -> Path = {
        Resource.CACHE.toPath().resolve(ScriptCompilationCache.generation())
    },
    private val runtimeClasspath: () -> ScriptRuntimeClasspath = ::scriptRuntimeClasspath
) {
    fun compile(project: ScriptProjectSource): ResultWithDiagnostics<CompiledScript> {
        val runtimeClasspath = try {
            runtimeClasspath()
        } catch (exception: Exception) {
            return project.operationalFailure(exception)
        }
        val compiler: KotlinIncrementalProjectCompiler
        val compilation = try {
            compiler = KotlinIncrementalProjectCompiler(
                cacheRoot = cacheRoot(),
                classpath = runtimeClasspath.files.map { file -> file.toPath() },
                classpathIdentity = runtimeClasspath.fingerprint,
                implementationClassLoader = ScriptProjectCompiler::class.java.classLoader
            )
            compiler.compile(project.module)
        } catch (exception: LinkageError) {
            return project.operationalFailure(exception)
        } catch (exception: Exception) {
            return project.operationalFailure(exception)
        }

        val cleanupReports = runCatching {
            compiler.pruneCaches(
                buildSet {
                    addAll(ScriptCompilationCache.retainedGenerationJars())
                    compilation.generationJar?.let { jar -> add(jar) }
                }
            ).failures.map { (path, message) ->
                ScriptDiagnostic(
                    ScriptDiagnostic.unspecifiedError,
                    "Could not prune incremental compiler cache $path: $message",
                    ScriptDiagnostic.Severity.WARNING,
                    PROJECT_SCRIPT_NAME,
                    null,
                    null
                )
            }
        }.getOrElse { exception ->
            listOf(
                ScriptDiagnostic(
                    ScriptDiagnostic.unspecifiedError,
                    "Could not prune incremental compiler caches: " +
                        (exception.message ?: exception.javaClass.name),
                    ScriptDiagnostic.Severity.WARNING,
                    PROJECT_SCRIPT_NAME,
                    null,
                    null
                )
            )
        }
        val reports = compilation.diagnostics.map(
            KotlinIncrementalProjectDiagnostic::toScriptDiagnostic
        ) + cleanupReports
        val jar = compilation.generationJar
        return if (compilation.isSuccess && jar != null) {
            val referenceAnalysis = try {
                ScriptClassReferenceAnalyzer.analyze(
                    jar,
                    runtimeClasspath.pluginSnapshot
                )
            } catch (exception: ScriptClassIdentityConflictException) {
                return ResultWithDiagnostics.Failure(
                    reports + project.classpathConflictDiagnostic(exception)
                )
            } catch (exception: LinkageError) {
                return project.operationalFailure(exception)
            } catch (exception: Exception) {
                return project.operationalFailure(exception)
            }
            ResultWithDiagnostics.Success(
                KotlinModuleCompiledScript(
                    project = project,
                    generationJar = jar,
                    runtimeDependencyFiles = runtimeClasspath.libraryFiles.map { file ->
                        file.toPath().toAbsolutePath().normalize()
                    },
                    classpathSnapshot = runtimeClasspath.pluginSnapshot,
                    ownedClassNames = referenceAnalysis.declaredClassNames,
                    pluginDependencyNames = referenceAnalysis.pluginOwnerNames
                ),
                reports
            )
        } else {
            ResultWithDiagnostics.Failure(
                reports.ifEmpty {
                    listOf(
                        ScriptDiagnostic(
                            ScriptDiagnostic.unspecifiedError,
                            "Kotlin project compilation failed: ${compilation.result}",
                            ScriptDiagnostic.Severity.ERROR,
                            PROJECT_SCRIPT_NAME,
                            null,
                            null
                        )
                    )
                }
            )
        }
    }

}

/**
 * The compiled representation is shared by the backend facade and evaluator,
 * but remains an implementation detail of the Kotlin project pipeline.
 */
internal data class KotlinModuleCompiledScript(
    val project: ScriptProjectSource,
    val generationJar: Path,
    val runtimeDependencyFiles: List<Path>,
    val classpathSnapshot: ScriptPluginClasspathSnapshot,
    val ownedClassNames: Set<String>,
    val pluginDependencyNames: Set<String>
) : CompiledScript {
    override val sourceLocationId: String = PROJECT_SCRIPT_NAME
    override val compilationConfiguration: ScriptCompilationConfiguration =
        ScriptCompilationConfiguration()

    override suspend fun getClass(
        scriptEvaluationConfiguration: ScriptEvaluationConfiguration?
    ): ResultWithDiagnostics<KClass<*>> =
        ResultWithDiagnostics.Failure(
            ScriptDiagnostic(
                ScriptDiagnostic.unspecifiedError,
                "Module artifacts are evaluated by ScriptGenerationEvaluator.",
                ScriptDiagnostic.Severity.ERROR
            )
        )
}

private fun KotlinIncrementalProjectDiagnostic.toScriptDiagnostic(): ScriptDiagnostic {
    val start = if (line != null && column != null) {
        SourceCode.Position(line, column)
    } else {
        null
    }
    val end = if (endLine != null && endColumn != null) {
        SourceCode.Position(endLine, endColumn)
    } else {
        start
    }
    return ScriptDiagnostic(
        code = if (isError) {
            ScriptDiagnostic.unspecifiedError
        } else {
            ScriptDiagnostic.unspecifiedInfo
        },
        message = message,
        severity = when (severity) {
            CompilerMessageRenderer.Severity.ERROR -> ScriptDiagnostic.Severity.ERROR
            CompilerMessageRenderer.Severity.WARNING -> ScriptDiagnostic.Severity.WARNING
            CompilerMessageRenderer.Severity.INFO -> ScriptDiagnostic.Severity.INFO
            CompilerMessageRenderer.Severity.DEBUG -> ScriptDiagnostic.Severity.DEBUG
        },
        sourcePath = sourceName,
        location = start?.let { SourceCode.Location(it, end ?: it) },
        exception = null
    )
}

private fun ScriptProjectSource.operationalFailure(
    exception: Throwable
): ResultWithDiagnostics.Failure =
    ResultWithDiagnostics.Failure(
        ScriptDiagnostic(
            ScriptDiagnostic.unspecifiedException,
            exception.message ?: exception.javaClass.name,
            ScriptDiagnostic.Severity.FATAL,
            PROJECT_SCRIPT_NAME,
            null,
            exception
        )
    )

private fun ScriptProjectSource.classpathConflictDiagnostic(
    exception: ScriptClassIdentityConflictException
): ScriptDiagnostic =
    ScriptDiagnostic(
        ScriptDiagnostic.unspecifiedError,
        exception.message ?: "Script plugin class identity conflict.",
        ScriptDiagnostic.Severity.ERROR,
        PROJECT_SCRIPT_NAME,
        null,
        exception
    )
