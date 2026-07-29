package eternalScript.core.script.project

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.manager.ConfigManager
import eternalScript.core.script.Script
import eternalScript.core.script.definition.ScriptCompilationCache
import eternalScript.core.script.definition.scriptRuntimeClasspath
import eternalScript.core.the.Root
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass
import kotlin.script.experimental.api.*

/**
 * Runtime compilation boundary for a complete script project.
 *
 * The default implementation compiles generated ordinary Kotlin files as one
 * incremental module while generation activation and rollback remain outside
 * the compiler.
 */
internal interface ScriptProjectBackend {
    fun compile(project: ScriptProjectSource): ResultWithDiagnostics<CompiledScript>

    fun evaluate(compiledScript: CompiledScript): ResultWithDiagnostics<EvaluationResult>
}

internal class KotlinProjectBackend(
    private val cacheRoot: () -> Path = {
        Resource.CACHE.toPath().resolve(ScriptCompilationCache.generation())
    },
    private val parentClassLoader: () -> ClassLoader = {
        Root.classLoader(ConfigManager.value(Config.CLASS_LOADER))
            ?: KotlinProjectBackend::class.java.classLoader
    }
) : ScriptProjectBackend {
    override fun compile(project: ScriptProjectSource): ResultWithDiagnostics<CompiledScript> {
        val runtimeClasspath = try {
            scriptRuntimeClasspath()
        } catch (exception: Exception) {
            return project.operationalFailure(exception)
        }
        val compiler: KotlinIncrementalProjectCompiler
        val compilation = try {
            compiler = KotlinIncrementalProjectCompiler(
                cacheRoot = cacheRoot(),
                classpath = runtimeClasspath.files.map { file -> file.toPath() },
                implementationClassLoader = KotlinProjectBackend::class.java.classLoader
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
            ResultWithDiagnostics.Success(
                KotlinModuleCompiledScript(
                    project = project,
                    generationJar = jar,
                    facadeClassNames = project.module.files.mapNotNull(
                        ScriptProjectModuleFile::exportClassName
                    ).distinct(),
                    entryClassNames = project.module.files.mapNotNull(
                        ScriptProjectModuleFile::facadeClassName
                    ),
                    expectedEntryPointCount = project.module.files.count { file ->
                        file.entryPoint != null
                    },
                    runtimeDependencyFiles = runtimeClasspath.libraryFiles.map { file ->
                        file.toPath().toAbsolutePath().normalize()
                    }
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

    override fun evaluate(
        compiledScript: CompiledScript
    ): ResultWithDiagnostics<EvaluationResult> {
        if (compiledScript !is KotlinModuleCompiledScript) {
            return ResultWithDiagnostics.Failure(
                ScriptDiagnostic(
                    ScriptDiagnostic.unspecifiedError,
                    "KotlinProjectBackend cannot evaluate this compiled artifact.",
                    ScriptDiagnostic.Severity.ERROR,
                    PROJECT_SCRIPT_NAME,
                    null,
                    null
                )
            )
        }

        val loader = URLClassLoader(
            (
                listOf(compiledScript.generationJar) +
                    compiledScript.runtimeDependencyFiles
                ).map { path -> path.toUri().toURL() }.toTypedArray(),
            parentClassLoader()
        )
        var script: Script? = null
        var runtimeResourceAttached = false
        var runtimeResource: KotlinModuleRuntimeResource? = null
        return try {
            val entryClasses = compiledScript.entryClassNames.map { className ->
                Class.forName(className, false, loader)
            }
            validateCompiledEntryPoints(
                entryClasses,
                compiledScript.expectedEntryPointCount
            )
            val facades = compiledScript.facadeClassNames.map { className ->
                Class.forName(className, false, loader)
            }
            val bootstrap = Class.forName(
                GENERATED_BOOTSTRAP_CLASS,
                true,
                loader
            )
            script = bootstrap.getMethod("create").invoke(null) as Script
            script.attachProjectFunctions(facades)
            val resource = KotlinModuleRuntimeResource(
                loader,
                compiledScript.generationJar
            )
            runtimeResource = resource
            script.attachRuntimeResource(resource)
            runtimeResourceAttached = true
            ResultWithDiagnostics.Success(
                EvaluationResult(
                    ResultValue.Unit(script::class, script),
                    ScriptEvaluationConfiguration()
                )
            )
        } catch (exception: Throwable) {
            val initialization = exception.findInitializationFailure()
            val partialScript = initialization?.script ?: script
            val failure = initialization?.cause ?: exception.unwrapReflectionFailure()
            partialScript?.let { staged ->
                runCatching(staged::disposeRuntime)
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            if (!runtimeResourceAttached) {
                runCatching {
                    runtimeResource?.close() ?: loader.close()
                }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            ResultWithDiagnostics.Failure(
                compiledScript.project.evaluationDiagnostic(failure)
            )
        }
    }

}

private data class KotlinModuleCompiledScript(
    val project: ScriptProjectSource,
    val generationJar: Path,
    val facadeClassNames: List<String>,
    val entryClassNames: List<String>,
    val expectedEntryPointCount: Int,
    val runtimeDependencyFiles: List<Path>
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
                "Module artifacts are evaluated by KotlinProjectBackend.",
                ScriptDiagnostic.Severity.ERROR
            )
        )
}

internal fun validateCompiledEntryPoints(
    facadeClasses: Iterable<Class<*>>,
    expectedCount: Int
) {
    val compiledCount = facadeClasses
        .asSequence()
        .flatMap { type -> type.declaredMethods.asSequence() }
        .count { method ->
            method.isAnnotationPresent(EternalScriptEntry::class.java)
        }
    check(compiledCount == expectedCount) {
        "Compiled Kotlin contains $compiledCount @EternalScriptEntry function(s), " +
            "but source discovery found $expectedCount. Use the annotation's fully qualified " +
            "name or a direct import; wildcard imports and Kotlin type aliases are not supported " +
            "for entry markers."
    }
}

private class KotlinModuleRuntimeResource(
    private val loader: URLClassLoader,
    private val generationJar: Path
) : AutoCloseable {
    private val closed = AtomicBoolean()

    init {
        ScriptCompilationCache.retain(generationJar.toFile())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            loader.close()
        } finally {
            ScriptCompilationCache.release(generationJar.toFile())
        }
    }
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

private fun ScriptProjectSource.evaluationDiagnostic(
    exception: Throwable
): ScriptDiagnostic {
    val position = runtimePosition(exception)
    val start = position?.let { SourceCode.Position(it.line, it.column) }
    return ScriptDiagnostic(
        ScriptDiagnostic.unspecifiedException,
        exception.message ?: exception.javaClass.name,
        ScriptDiagnostic.Severity.ERROR,
        position?.sourceName ?: PROJECT_SCRIPT_NAME,
        start?.let { SourceCode.Location(it) },
        exception
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

private fun Throwable.findInitializationFailure(): ScriptProjectInitializationException? {
    val pending = ArrayDeque<Throwable>()
    val visited = mutableSetOf<Throwable>()
    pending += this
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current is ScriptProjectInitializationException) return current
        current.cause?.let(pending::addLast)
        current.suppressed.forEach(pending::addLast)
    }
    return null
}

private fun Throwable.unwrapReflectionFailure(): Throwable {
    var current = this
    while (true) {
        current = when (current) {
            is InvocationTargetException -> current.targetException ?: return current
            is ExceptionInInitializerError -> current.exception ?: return current
            else -> return current
        }
    }
}
