@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package eternalScript.core.script.project

import org.jetbrains.kotlin.buildtools.api.BaseCompilationOperation
import org.jetbrains.kotlin.buildtools.api.BaseIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.classpathSnapshottingOperation
import org.jetbrains.kotlin.buildtools.api.jvm.jvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.snapshotBasedIcConfiguration
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

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

/**
 * Compiles generated ordinary Kotlin sources as one incrementally-built JVM
 * module and snapshots every successful output as an immutable generation JAR.
 *
 * The mutable source, class, and incremental-cache directories are never
 * intended to be used by a live generation classloader.
 */
internal class KotlinIncrementalProjectCompiler(
    cacheRoot: Path,
    classpath: List<Path>,
    implementationClassLoader: ClassLoader =
        Thread.currentThread().contextClassLoader
            ?: KotlinIncrementalProjectCompiler::class.java.classLoader
) {
    private val toolchains = KotlinToolchains.loadImplementation(implementationClassLoader)
    private val jvm = toolchains.jvm
    private val normalizedClasspath = classpath.map { path ->
        path.toAbsolutePath().normalize()
    }
    private val workspaceRoot =
        cacheRoot.toAbsolutePath().normalize().resolve(CACHE_NAMESPACE)

    internal val generatedSourcesDirectory: Path = workspaceRoot.resolve("sources")
    internal val classesDirectory: Path = workspaceRoot.resolve("classes")
    internal val classpathSnapshotsDirectory: Path =
        workspaceRoot.resolve("classpath-snapshots")
    internal val artifactsDirectory: Path = workspaceRoot.resolve("artifacts")

    private val incrementalDirectory = workspaceRoot.resolve("incremental")
    private val compiledSourceStateFile = workspaceRoot.resolve("compiled-sources.tsv")

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
        val artifactCleanup = pruneDirectory(
            artifactsDirectory,
            maxGenerationJars,
            cutoff,
            retained
        )
        val snapshotCleanup = pruneDirectory(
            classpathSnapshotsDirectory,
            maxClasspathSnapshots,
            cutoff,
            emptySet()
        )
        return KotlinIncrementalProjectCacheCleanup(
            removedGenerationJars = artifactCleanup.removed,
            removedClasspathSnapshots = snapshotCleanup.removed,
            failures = artifactCleanup.failures + snapshotCleanup.failures
        )
    }

    @Synchronized
    fun compile(module: ScriptProjectModule): KotlinIncrementalProjectCompilation {
        val classpathState = try {
            classpathState()
        } catch (exception: Exception) {
            return failed(exception)
        }
        val artifactKey = artifactKey(module, classpathState.fingerprint)
        val artifact = artifactsDirectory.resolve("$artifactKey.jar")

        if (artifact.isUsableGenerationJar(module)) {
            return KotlinIncrementalProjectCompilation(
                result = CompilationResult.COMPILATION_SUCCESS,
                generationJar = artifact,
                diagnostics = emptyList(),
                cacheHit = true
            )
        }

        return try {
            prepareDirectories()
            val sourceChanges = syncSources(module)
            val renderer = CapturingRenderer(module)
            val result = toolchains.createBuildSession().use { session ->
                val snapshots = classpathState.entries.map { entry ->
                    entry.snapshot(session)
                }
                val operation = jvm.jvmCompilationOperation(
                    sources = sourceChanges.sources,
                    destinationDirectory = classesDirectory
                ) {
                    this[BaseCompilationOperation.COMPILER_MESSAGE_RENDERER] = renderer
                    compilerArguments[JvmCompilerArguments.CLASSPATH] = normalizedClasspath
                    compilerArguments[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_21
                    compilerArguments[JvmCompilerArguments.MODULE_NAME] = MODULE_NAME
                    compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
                    compilerArguments[JvmCompilerArguments.NO_REFLECT] = true

                    this[JvmCompilationOperation.INCREMENTAL_COMPILATION] =
                        snapshotBasedIcConfiguration(
                            workingDirectory = incrementalDirectory,
                            sourcesChanges = SourcesChanges.Known(
                                modifiedFiles = sourceChanges.modified.map(Path::toFile),
                                removedFiles = sourceChanges.removed.map(Path::toFile)
                            ),
                            dependenciesSnapshotFiles = snapshots
                        ) {
                            this[BaseIncrementalCompilationConfiguration.ROOT_PROJECT_DIR] =
                                workspaceRoot
                            this[BaseIncrementalCompilationConfiguration.MODULE_BUILD_DIR] =
                                workspaceRoot
                            this[BaseIncrementalCompilationConfiguration.BACKUP_CLASSES] = true
                            this[
                                BaseIncrementalCompilationConfiguration
                                    .KEEP_IC_CACHES_IN_MEMORY
                            ] = true
                            this[
                                BaseIncrementalCompilationConfiguration
                                    .TRACK_CONFIGURATION_INPUTS
                            ] = true
                        }
                }
                session.executeOperation(
                    operation,
                    toolchains.createInProcessExecutionPolicy(),
                    QUIET_LOGGER
                )
            }

            if (result != CompilationResult.COMPILATION_SUCCESS) {
                KotlinIncrementalProjectCompilation(
                    result = result,
                    generationJar = null,
                    diagnostics = renderer.diagnostics(),
                    cacheHit = false
                )
            } else {
                persistCompiledSourceState(sourceChanges.currentState)
                val packaged = packageGeneration(artifact, module)
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

    private fun prepareDirectories() {
        Files.createDirectories(generatedSourcesDirectory)
        Files.createDirectories(classesDirectory)
        Files.createDirectories(classpathSnapshotsDirectory)
        Files.createDirectories(artifactsDirectory)
        Files.createDirectories(incrementalDirectory)
    }

    private fun pruneDirectory(
        directory: Path,
        maxFiles: Int,
        cutoff: Instant,
        retained: Set<Path>
    ): CachePruneBatch {
        if (!Files.isDirectory(directory)) return CachePruneBatch.EMPTY

        val files = Files.list(directory).use { paths ->
            paths.filter(Path::isRegularFile)
                .map { path ->
                    CacheFile(
                        path = path.toAbsolutePath().normalize(),
                        modified = Files.getLastModifiedTime(path).toInstant()
                    )
                }
                .sorted(
                    compareByDescending<CacheFile>(CacheFile::modified)
                        .thenBy { file -> file.path.invariantSeparatorsPathString }
                )
                .toList()
        }
        val retainedCount = files.count { file -> file.path in retained }
        var available = (maxFiles - retainedCount).coerceAtLeast(0)
        val removed = mutableListOf<Path>()
        val failures = linkedMapOf<Path, String>()

        files.forEach { file ->
            if (file.path in retained) return@forEach
            val keep = file.modified >= cutoff && available > 0
            if (keep) {
                available -= 1
                return@forEach
            }
            try {
                if (Files.deleteIfExists(file.path)) {
                    removed.add(file.path)
                }
            } catch (exception: Exception) {
                failures[file.path] = exception.message ?: exception.javaClass.name
            }
        }
        return CachePruneBatch(removed, failures)
    }

    private fun syncSources(module: ScriptProjectModule): SourceChanges {
        val previousState = readCompiledSourceState()
            ?: physicalSourceNames().associateWith { UNCOMPILED_SOURCE }
        val expected = module.files.associate { file ->
            val target = generatedSourcePath(file.name)
            val relativeName = target.relativeSourceName()
            relativeName to ExpectedSource(target, file.text)
        }
        check(expected.size == module.files.size) {
            "Generated Kotlin source paths must be unique."
        }
        expected.values.forEach { source ->
            writeIfChanged(source.path, source.text)
        }

        val expectedPaths = expected.values.mapTo(linkedSetOf(), ExpectedSource::path)
        val staleFiles = physicalSourcePaths()
            .filter { path -> path !in expectedPaths }
        try {
            staleFiles.forEach(Files::delete)
        } finally {
            removeEmptySourceDirectories()
        }

        val currentState = expected.mapValues { (_, source) ->
            source.text.sha256()
        }
        val modified = currentState.entries
            .filter { (name, hash) -> previousState[name] != hash }
            .map { (name, _) -> generatedSourcePath(name) }
        val removed = previousState.keys
            .filter { name -> name !in currentState }
            .map(::generatedSourcePath)
        return SourceChanges(
            sources = expected.values.map(ExpectedSource::path),
            modified = modified,
            removed = removed,
            currentState = currentState
        )
    }

    internal fun generatedSourcePath(relativeName: String): Path {
        val relative = Path.of(relativeName)
        require(
            !relative.isAbsolute &&
                relative.nameCount > 0 &&
                relative.none { segment ->
                    val value = segment.toString()
                    value == "." || value == ".."
                }
        ) {
            "Generated source path must be a normalized relative path: $relativeName"
        }
        val target = generatedSourcesDirectory.resolve(relative).normalize()
        require(
            target != generatedSourcesDirectory &&
                target.startsWith(generatedSourcesDirectory)
        ) {
            "Generated source path escapes the source cache: $relativeName"
        }
        return target
    }

    private fun Path.relativeSourceName(): String {
        val normalized = toAbsolutePath().normalize()
        require(
            normalized != generatedSourcesDirectory &&
                normalized.startsWith(generatedSourcesDirectory)
        ) {
            "Generated source is outside the source cache: $this"
        }
        return generatedSourcesDirectory.relativize(normalized)
            .invariantSeparatorsPathString
    }

    private fun physicalSourcePaths(): List<Path> {
        if (!Files.isDirectory(generatedSourcesDirectory)) return emptyList()
        return Files.walk(generatedSourcesDirectory).use { paths ->
            paths.filter(Path::isRegularFile)
                .map { path -> path.toAbsolutePath().normalize() }
                .sorted()
                .toList()
        }
    }

    private fun physicalSourceNames(): List<String> =
        physicalSourcePaths().map { path -> path.relativeSourceName() }

    private fun removeEmptySourceDirectories() {
        if (!Files.isDirectory(generatedSourcesDirectory)) return
        val directories = Files.walk(generatedSourcesDirectory).use { paths ->
            paths.filter(Path::isDirectory)
                .filter { path -> path != generatedSourcesDirectory }
                .sorted(
                    compareByDescending<Path>(Path::getNameCount)
                        .thenByDescending { path ->
                            path.invariantSeparatorsPathString
                        }
                )
                .toList()
        }
        directories.forEach { directory ->
            runCatching {
                Files.deleteIfExists(directory)
            }
        }
    }

    private fun readCompiledSourceState(): Map<String, String>? {
        if (!compiledSourceStateFile.isRegularFile()) return null
        return runCatching {
            val lines = Files.readAllLines(
                compiledSourceStateFile,
                StandardCharsets.UTF_8
            )
            check(lines.firstOrNull() == SOURCE_STATE_SCHEMA)
            lines.drop(1).associate { line ->
                val separator = line.indexOf('\t')
                check(separator > 0 && separator < line.lastIndex)
                line.substring(0, separator) to line.substring(separator + 1)
            }
        }.getOrNull()
    }

    private fun persistCompiledSourceState(state: Map<String, String>) {
        val text = buildString {
            appendLine(SOURCE_STATE_SCHEMA)
            state.toSortedMap().forEach { (name, hash) ->
                check('\t' !in name && '\n' !in name && '\r' !in name)
                append(name)
                append('\t')
                appendLine(hash)
            }
        }
        writeIfChanged(compiledSourceStateFile, text)
    }

    private fun writeIfChanged(target: Path, text: String): Boolean {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (
            target.isRegularFile() &&
            Files.size(target) == bytes.size.toLong() &&
            Files.readAllBytes(target).contentEquals(bytes)
        ) {
            return false
        }

        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(
            target.parent,
            ".${target.fileName}.",
            ".tmp"
        )
        try {
            Files.write(
                temporary,
                bytes,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            moveReplacing(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return true
    }

    private fun classpathState(): ClasspathState {
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = normalizedClasspath.mapIndexed { index, path ->
            require(Files.exists(path)) {
                "Compilation classpath entry does not exist: $path"
            }
            val fingerprint = path.contentFingerprint()
            digest.updateField(index.toString())
            digest.updateField(path.invariantSeparatorsPathString)
            digest.updateField(fingerprint)
            ClasspathStateEntry(path, fingerprint)
        }
        return ClasspathState(entries, digest.digest().toHexString())
    }

    private fun ClasspathStateEntry.snapshot(
        session: KotlinToolchains.BuildSession
    ): Path {
        val target = classpathSnapshotsDirectory.resolve("$fingerprint.snapshot")
        if (target.isRegularFile() && Files.size(target) > 0L) {
            return target
        }

        val snapshot = session.executeOperation(
            jvm.classpathSnapshottingOperation(path)
        )
        val temporary = Files.createTempFile(
            classpathSnapshotsDirectory,
            ".$fingerprint.",
            ".tmp"
        )
        try {
            snapshot.saveSnapshot(temporary)
            moveReplacing(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    private fun artifactKey(
        module: ScriptProjectModule,
        classpathFingerprint: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField(CACHE_SCHEMA)
        digest.updateField(toolchains.getCompilerVersion())
        digest.updateField(MODULE_NAME)
        digest.updateField(JvmTarget.JVM_21.stringValue)
        digest.updateField(module.fingerprint)
        digest.updateField(classpathFingerprint)
        return digest.digest().toHexString()
    }

    private fun packageGeneration(
        target: Path,
        module: ScriptProjectModule
    ): Path {
        val bootstrapClass = classesDirectory.resolve(
            GENERATED_BOOTSTRAP_CLASS.replace('.', '/') + ".class"
        )
        check(bootstrapClass.isRegularFile()) {
            "Compiled project did not contain $GENERATED_BOOTSTRAP_CLASS."
        }

        val files = Files.walk(classesDirectory).use { paths ->
            paths.filter(Path::isRegularFile)
                .sorted(compareBy { path ->
                    classesDirectory.relativize(path).invariantSeparatorsPathString
                })
                .toList()
        }
        val temporary = Files.createTempFile(
            artifactsDirectory,
            ".${target.fileName}.",
            ".tmp"
        )
        try {
            JarOutputStream(
                BufferedOutputStream(
                    Files.newOutputStream(
                        temporary,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                )
            ).use { jar ->
                files.forEach { file ->
                    val bytes = Files.readAllBytes(file)
                    val crc = CRC32().apply { update(bytes) }
                    val entry = JarEntry(
                        classesDirectory.relativize(file).invariantSeparatorsPathString
                    ).apply {
                        method = JarEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        this.crc = crc.value
                        time = DETERMINISTIC_JAR_TIMESTAMP
                    }
                    jar.putNextEntry(entry)
                    jar.write(bytes)
                    jar.closeEntry()
                }
            }
            publishArtifact(temporary, target, module)
        } finally {
            Files.deleteIfExists(temporary)
        }
        check(target.isUsableGenerationJar(module)) {
            "Generation JAR was not readable after publication: $target"
        }
        return target
    }

    private fun publishArtifact(
        temporary: Path,
        target: Path,
        module: ScriptProjectModule
    ) {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            if (!target.isUsableGenerationJar(module)) {
                moveReplacing(temporary, target)
            }
        } catch (_: AtomicMoveNotSupportedException) {
            moveReplacing(temporary, target)
        }
    }

    private fun Path.isUsableGenerationJar(
        module: ScriptProjectModule
    ): Boolean {
        if (!isRegularFile() || runCatching { Files.size(this) }.getOrDefault(0L) <= 0L) {
            return false
        }
        val requiredEntries = buildSet {
            add(GENERATED_BOOTSTRAP_CLASS.replace('.', '/') + ".class")
            module.files.mapNotNullTo(this) { file ->
                file.facadeClassName?.replace('.', '/')?.plus(".class")
            }
        }
        return runCatching {
            JarFile(toFile(), false).use { jar ->
                requiredEntries.all { entry -> jar.getJarEntry(entry) != null }
            }
        }.getOrDefault(false)
    }

    private fun Path.contentFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (isRegularFile()) {
            digest.updateField("file")
            digest.updateFile(this)
            return digest.digest().toHexString()
        }
        require(isDirectory()) {
            "Unsupported compilation classpath entry: $this"
        }
        digest.updateField("directory")
        Files.walk(this).use { paths ->
            paths.sorted(compareBy { path ->
                relativize(path).invariantSeparatorsPathString
            }).forEach { path ->
                val relative = relativize(path).invariantSeparatorsPathString
                when {
                    path.isDirectory() -> {
                        digest.updateField("directory")
                        digest.updateField(relative)
                    }

                    path.isRegularFile() -> {
                        digest.updateField("file")
                        digest.updateField(relative)
                        digest.updateFile(path)
                    }

                    else -> {
                        digest.updateField("other")
                        digest.updateField(relative)
                    }
                }
            }
        }
        return digest.digest().toHexString()
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

    private data class SourceChanges(
        val sources: List<Path>,
        val modified: List<Path>,
        val removed: List<Path>,
        val currentState: Map<String, String>
    )

    private data class ExpectedSource(
        val path: Path,
        val text: String
    )

    private data class ClasspathState(
        val entries: List<ClasspathStateEntry>,
        val fingerprint: String
    )

    private data class ClasspathStateEntry(
        val path: Path,
        val fingerprint: String
    )

    private data class CacheFile(
        val path: Path,
        val modified: Instant
    )

    private data class CachePruneBatch(
        val removed: List<Path>,
        val failures: Map<Path, String>
    ) {
        companion object {
            val EMPTY = CachePruneBatch(emptyList(), emptyMap())
        }
    }

    private companion object {
        private const val CACHE_NAMESPACE = "kotlin-incremental-v1"
        private const val CACHE_SCHEMA = "eternal-script-kotlin-incremental-v1"
        private const val MODULE_NAME = "eternal-script-project"
        private const val DETERMINISTIC_JAR_TIMESTAMP = 0L
        private const val SOURCE_STATE_SCHEMA = "eternal-script-compiled-sources-v2"
        private const val UNCOMPILED_SOURCE = "<uncompiled>"
        private const val DEFAULT_MAX_GENERATION_JARS = 32
        private const val DEFAULT_MAX_CLASSPATH_SNAPSHOTS = 256
        private val DEFAULT_CACHE_MAX_AGE: Duration = Duration.ofDays(30)

        private val QUIET_LOGGER = object : KotlinLogger {
            override val isDebugEnabled = false

            override fun error(msg: String, throwable: Throwable?) = Unit

            override fun warn(msg: String, throwable: Throwable?) = Unit

            override fun info(msg: String) = Unit

            override fun debug(msg: String) = Unit

            override fun lifecycle(msg: String) = Unit
        }
    }
}

private fun CompilerMessageRenderer.SourceLocation.validStart(): Pair<Int, Int>? =
    if (line > 0 && column > 0) line to column else null

private fun CompilerMessageRenderer.SourceLocation.validEnd(): Pair<Int, Int>? =
    if (lineEnd > 0 && columnEnd > 0) lineEnd to columnEnd else null

private fun String.sourceFileName(): String =
    substringAfterLast('/').substringAfterLast('\\')

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .toHexString()

private fun Int.isPositive(): Boolean = this > 0

private fun MessageDigest.updateFile(path: Path) {
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            update(buffer, 0, count)
        }
    }
}

private fun MessageDigest.updateField(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private fun moveReplacing(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private const val HASH_BUFFER_SIZE = 64 * 1024
