package eternalScript.core.script.project

import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

internal data class KotlinIncrementalProjectDiagnostic(
    val severity: CompilerMessageRenderer.Severity,
    val message: String,
    val sourceName: String?,
    val line: Int?,
    val column: Int?,
    val endLine: Int?,
    val endColumn: Int?,
    val lineContent: String?
) {
    val isError: Boolean
        get() = severity == CompilerMessageRenderer.Severity.ERROR
}

internal data class KotlinIncrementalProjectCompilation(
    val result: CompilationResult,
    val generationJar: Path?,
    val diagnostics: List<KotlinIncrementalProjectDiagnostic>,
    val cacheHit: Boolean
) {
    val isSuccess: Boolean
        get() = result == CompilationResult.COMPILATION_SUCCESS && generationJar != null
}

internal data class KotlinIncrementalProjectCacheCleanup(
    val removedGenerationJars: List<Path>,
    val removedClasspathSnapshots: List<Path>,
    val failures: Map<Path, String>
)

internal enum class KotlinIncrementalProjectCompilationPhase {
    BEFORE_CACHE_HIT_REVALIDATION,
    BEFORE_CLASSPATH_SNAPSHOTS,
    BEFORE_CLASSPATH_REVALIDATION
}

/**
 * Thin compiler facade coordinating an isolated workspace, classpath snapshot
 * store, Build Tools compiler, and immutable generation artifact store.
 */
internal class KotlinIncrementalProjectCompiler(
    cacheRoot: Path,
    classpath: List<Path>,
    classpathIdentity: String = "",
    implementationClassLoader: ClassLoader =
        Thread.currentThread().contextClassLoader
            ?: KotlinIncrementalProjectCompiler::class.java.classLoader,
    private val compilationObserver: (KotlinIncrementalProjectCompilationPhase) -> Unit = {}
) {
    private val workspace = KotlinCompilerWorkspace(cacheRoot)
    private val classpathSnapshots = KotlinClasspathSnapshotStore(
        classpath = classpath,
        classpathIdentity = classpathIdentity,
        snapshotsDirectory = workspace.classpathSnapshotsDirectory
    )
    private val buildTools = KotlinBuildToolsCompiler(
        implementationClassLoader = implementationClassLoader,
        classpath = classpathSnapshots.classpath
    )
    private val artifacts = GenerationArtifactStore(
        artifactsDirectory = workspace.artifactsDirectory,
        classesDirectory = workspace.classesDirectory,
        compilerVersion = buildTools.compilerVersion
    )

    internal val generatedSourcesDirectory: Path
        get() = workspace.generatedSourcesDirectory
    internal val classesDirectory: Path
        get() = workspace.classesDirectory
    internal val classpathSnapshotsDirectory: Path
        get() = classpathSnapshots.snapshotsDirectory
    internal val artifactsDirectory: Path
        get() = artifacts.artifactsDirectory

    internal fun generatedSourcePath(relativeName: String): Path =
        workspace.generatedSourcePath(relativeName)

    /**
     * Bounds immutable compiler caches without deleting an artifact that a
     * live or rollback generation still needs.
     */
    @Synchronized
    fun pruneCaches(
        retainedGenerationJars: Set<Path>,
        maxGenerationJars: Int = DEFAULT_MAX_GENERATION_JARS,
        maxClasspathSnapshots: Int = DEFAULT_MAX_CLASSPATH_SNAPSHOTS,
        maxAge: Duration = DEFAULT_CACHE_MAX_AGE
    ): KotlinIncrementalProjectCacheCleanup {
        require(maxGenerationJars >= 0) {
            "maxGenerationJars must not be negative."
        }
        require(maxClasspathSnapshots >= 0) {
            "maxClasspathSnapshots must not be negative."
        }
        require(!maxAge.isNegative) {
            "maxAge must not be negative."
        }

        val cutoff = Instant.now().minus(maxAge)
        val retained = retainedGenerationJars.mapTo(linkedSetOf()) { path ->
            path.toAbsolutePath().normalize()
        }
        val artifactCleanup = artifacts.prune(retained, maxGenerationJars, cutoff)
        val snapshotCleanup = classpathSnapshots.prune(maxClasspathSnapshots, cutoff)
        return KotlinIncrementalProjectCacheCleanup(
            removedGenerationJars = artifactCleanup.removed,
            removedClasspathSnapshots = snapshotCleanup.removed,
            failures = artifactCleanup.failures + snapshotCleanup.failures
        )
    }

    @Synchronized
    fun compile(module: ScriptProjectModule): KotlinIncrementalProjectCompilation {
        val classpathState = try {
            classpathSnapshots.capture()
        } catch (exception: Exception) {
            return failed(exception)
        }
        val artifact = artifacts.artifact(module, classpathState.fingerprint)

        if (artifacts.isUsable(artifact, module)) {
            return try {
                compilationObserver(
                    KotlinIncrementalProjectCompilationPhase.BEFORE_CACHE_HIT_REVALIDATION
                )
                classpathSnapshots.requireUnchanged(classpathState)
                KotlinIncrementalProjectCompilation(
                    result = CompilationResult.COMPILATION_SUCCESS,
                    generationJar = artifact,
                    diagnostics = emptyList(),
                    cacheHit = true
                )
            } catch (exception: Exception) {
                failed(exception)
            }
        }

        return try {
            workspace.prepare()
            val sourceChanges = workspace.syncSources(module)
            val renderer = CapturingRenderer(module)
            compilationObserver(
                KotlinIncrementalProjectCompilationPhase.BEFORE_CLASSPATH_SNAPSHOTS
            )
            val result = buildTools.compile(
                workspace = workspace,
                sourceChanges = sourceChanges,
                classpathState = classpathState,
                snapshotStore = classpathSnapshots,
                renderer = renderer
            )

            if (result != CompilationResult.COMPILATION_SUCCESS) {
                KotlinIncrementalProjectCompilation(
                    result = result,
                    generationJar = null,
                    diagnostics = renderer.diagnostics(),
                    cacheHit = false
                )
            } else {
                compilationObserver(
                    KotlinIncrementalProjectCompilationPhase.BEFORE_CLASSPATH_REVALIDATION
                )
                classpathSnapshots.requireUnchanged(classpathState)
                workspace.persistCompiledSourceState(sourceChanges.currentState)
                val packaged = artifacts.packageGeneration(artifact, module)
                KotlinIncrementalProjectCompilation(
                    result = result,
                    generationJar = packaged,
                    diagnostics = renderer.diagnostics(),
                    cacheHit = false
                )
            }
        } catch (exception: Exception) {
            failed(exception)
        }
    }

    private fun failed(exception: Exception) = KotlinIncrementalProjectCompilation(
        result = CompilationResult.COMPILER_INTERNAL_ERROR,
        generationJar = null,
        diagnostics = listOf(
            KotlinIncrementalProjectDiagnostic(
                severity = CompilerMessageRenderer.Severity.ERROR,
                message = exception.message ?: exception.javaClass.name,
                sourceName = null,
                line = null,
                column = null,
                endLine = null,
                endColumn = null,
                lineContent = null
            )
        ),
        cacheHit = false
    )

    private class CapturingRenderer(
        private val module: ScriptProjectModule
    ) : CompilerMessageRenderer {
        private val captured = CopyOnWriteArrayList<KotlinIncrementalProjectDiagnostic>()

        override fun render(
            severity: CompilerMessageRenderer.Severity,
            message: String,
            location: CompilerMessageRenderer.SourceLocation?
        ): String {
            if (severity != CompilerMessageRenderer.Severity.DEBUG) {
                val start = location?.validStart()?.let { (line, column) ->
                    module.position(location.path, line, column)
                }
                val end = location?.validEnd()?.let { (line, column) ->
                    module.position(location.path, line, column)
                }
                captured += KotlinIncrementalProjectDiagnostic(
                    severity = severity,
                    message = message,
                    sourceName = start?.sourceName ?: location?.path?.sourceFileName(),
                    line = start?.line ?: location?.line?.takeIf(Int::isPositive),
                    column = start?.column ?: location?.column?.takeIf(Int::isPositive),
                    endLine = end?.line ?: location?.lineEnd?.takeIf(Int::isPositive),
                    endColumn = end?.column ?: location?.columnEnd?.takeIf(Int::isPositive),
                    lineContent = location?.lineContent
                )
            }
            return ""
        }

        fun diagnostics(): List<KotlinIncrementalProjectDiagnostic> = captured.toList()
    }

    private companion object {
        private const val DEFAULT_MAX_GENERATION_JARS = 32
        private const val DEFAULT_MAX_CLASSPATH_SNAPSHOTS = 256
        private val DEFAULT_CACHE_MAX_AGE: Duration = Duration.ofDays(30)
    }
}

private fun CompilerMessageRenderer.SourceLocation.validStart(): Pair<Int, Int>? =
    if (line > 0 && column > 0) line to column else null

private fun CompilerMessageRenderer.SourceLocation.validEnd(): Pair<Int, Int>? =
    if (lineEnd > 0 && columnEnd > 0) lineEnd to columnEnd else null

private fun String.sourceFileName(): String =
    substringAfterLast('/').substringAfterLast('\\')

private fun Int.isPositive(): Boolean = this > 0
