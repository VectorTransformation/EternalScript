package eternalscript.scripting.compilation

import eternalscript.api.script.Script
import eternalscript.config.PluginPaths
import eternalscript.scripting.repl.SCRIPT_DEFAULT_IMPORTS
import eternalscript.scripting.repl.SCRIPT_JVM_TARGET
import eternalscript.scripting.repl.ScriptCompilerConfig
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.repl.ScriptingHostConfig
import eternalscript.scripting.repl.k2.K2_REPL_COMPILER_ABI
import eternalscript.util.Sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.StringFormat
import kotlin.metadata.jvm.KotlinClassMetadata
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingK2CompilerPluginRegistrar
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.annotations.NotNull
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.hostConfiguration
import kotlin.script.experimental.api.isStandalone
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.util.classpathFromClassloader
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.templates.standard.ScriptTemplateWithArgs
import kotlin.reflect.jvm.ExperimentalReflectionOnLambdas

internal data class ScriptEnvironmentSnapshot(
    val baseClassLoader: ClassLoader,
    val pluginClassLoaders: List<ClassLoader>,
    val libraryRoots: List<File>,
    val pluginVersion: String,
    val pluginArtifact: File?,
    val compilerRuntimeRoots: List<File> = emptyList()
)

internal data class ScriptCompilationEnvironment(
    val compilationConfiguration: ScriptCompilationConfiguration,
    val baseClassLoader: ClassLoader,
    val runtimeClassLoader: ClassLoader,
    val classpath: List<File>,
    val defaultImports: List<String>,
    val fingerprint: String,
    private val runtimeDependencies: RuntimeDependencies,
    private val pluginClassLoaders: List<ClassLoader> = emptyList()
) : AutoCloseable {
    fun hasSameClassLoaderIdentity(other: ScriptCompilationEnvironment): Boolean =
        baseClassLoader === other.baseClassLoader &&
            pluginClassLoaders.size == other.pluginClassLoaders.size &&
            pluginClassLoaders.indices.all { index ->
                pluginClassLoaders[index] === other.pluginClassLoaders[index]
            }

    fun retained(): ScriptCompilationEnvironment {
        runtimeDependencies.retain()
        return this
    }

    override fun close() {
        runtimeDependencies.close()
    }
}

internal class RuntimeDependencies(
    val classLoader: RuntimeDependencyClassLoader
) : AutoCloseable {
    private val references = AtomicInteger(1)

    fun retain() {
        while (true) {
            val current = references.get()
            check(current > 0) { "A disposed script runtime environment cannot be retained" }
            if (references.compareAndSet(current, current + 1)) return
        }
    }

    override fun close() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "A script runtime environment was closed more than once" }
        if (remaining == 0) classLoader.close()
    }
}

internal class RuntimeDependencyClassLoader(
    urls: Array<java.net.URL>,
    parent: ClassLoader,
    private val pluginClassLoaders: List<ClassLoader>
) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            try {
                return parent.loadClass(name)
            } catch (_: ClassNotFoundException) {}
            pluginClassLoaders.forEach { loader ->
                try {
                    return loader.loadClass(name)
                } catch (_: ClassNotFoundException) {}
            }
            return findClass(name).also { loaded -> if (resolve) resolveClass(loaded) }
        }
    }
}

internal object ScriptCompilationEnvironmentFactory {
    fun capture(
        plugin: JavaPlugin,
        paths: PluginPaths
    ): ScriptEnvironmentSnapshot {
        check(Bukkit.isPrimaryThread()) {
            "The Kotlin script environment must be captured on the Paper main thread"
        }

        val baseClassLoader = plugin.javaClass.classLoader
        val pluginClassLoaders = Bukkit.getPluginManager().plugins
            .map { installedPlugin -> installedPlugin.javaClass.classLoader }
            .distinct()
        val libraryRoots = listOf(paths.librariesDirectory)
        val compilerRuntimeRoots = listOf(
            KotlinVersion::class.java,
            ExperimentalReflectionOnLambdas::class.java,
            KotlinCompilerVersion::class.java,
            ScriptingK2CompilerPluginRegistrar::class.java,
            ScriptDefinition::class.java,
            NotNull::class.java,
            ScriptCompilationConfiguration::class.java,
            ScriptEvaluationConfiguration::class.java,
            JvmDependency::class.java,
            BasicJvmScriptingHost::class.java,
            ScriptTemplateWithArgs::class.java,
            KotlinClassMetadata::class.java,
            StringFormat::class.java,
            Json::class.java,
            CoroutineScope::class.java
        ).mapNotNull(::codeSourceFile)
            .distinctBy { file -> file.absolutePath.lowercase(Locale.ROOT) }

        return ScriptEnvironmentSnapshot(
            baseClassLoader = baseClassLoader,
            pluginClassLoaders = pluginClassLoaders,
            libraryRoots = libraryRoots,
            pluginVersion = plugin.pluginMeta.version,
            pluginArtifact = codeSourceFile(plugin.javaClass),
            compilerRuntimeRoots = compilerRuntimeRoots
        )
    }

    fun build(snapshot: ScriptEnvironmentSnapshot): ScriptCompilationEnvironment {
        val runtimeLibraryClasspath = buildSet {
            snapshot.libraryRoots.forEach { root ->
                when {
                    root.isFile && root.extension.equals("jar", ignoreCase = true) -> add(root)
                    root.isDirectory -> Files.walk(root.toPath()).use { paths ->
                        add(root)
                        paths.filter(Files::isRegularFile)
                            .filter { path -> path.fileName.toString().endsWith(".jar", ignoreCase = true) }
                            .forEach { path -> add(path.toFile()) }
                    }
                }
            }
        }.asSequence()
            .filter(File::exists)
            .map { file -> file.toPath().toAbsolutePath().normalize().toFile() }
            .distinctBy { file -> file.invariantSeparatorsPath.lowercase(Locale.ROOT) }
            .sortedBy { file -> file.invariantSeparatorsPath }
            .toList()
        val classpath = buildList {
            addAll(classpathFromClassloader(snapshot.baseClassLoader).orEmpty())
            snapshot.pluginArtifact?.let(::add)
            addAll(snapshot.compilerRuntimeRoots)
            snapshot.pluginClassLoaders
                .filterNot { classLoader -> classLoader === snapshot.baseClassLoader }
                .forEach { classLoader ->
                    addAll(classpathFromClassloader(classLoader).orEmpty())
                }
            addAll(runtimeLibraryClasspath)
        }.asSequence()
            .filter(File::exists)
            .map { file -> file.toPath().toAbsolutePath().normalize().toFile() }
            .distinctBy { file -> file.invariantSeparatorsPath.lowercase(Locale.ROOT) }
            .toList()
        val imports = SCRIPT_DEFAULT_IMPORTS
        val classpathDigests = classpath.map { file ->
            file.invariantSeparatorsPath to digestPath(file.toPath())
        }
        val importDigest = Sha256.text(imports.joinToString("\n"))
        val fingerprint = Sha256.text(buildString {
            appendLine("format=5")
            appendLine("compilerAdapter=$K2_REPL_COMPILER_ABI")
            appendLine("pluginVersion=${snapshot.pluginVersion}")
            appendLine("pluginArtifact=${snapshot.pluginArtifact?.invariantSeparatorsPath.orEmpty()}")
            appendLine("classLoader=${snapshot.baseClassLoader.javaClass.name}")
            appendLine("kotlinCompiler=${KotlinCompilerVersion.VERSION}")
            appendLine("kotlinRuntime=${KotlinVersion.CURRENT}")
            appendLine("java=${Runtime.version().feature()}")
            appendLine("scriptBase=${Script::class.qualifiedName}")
            appendLine("extension=eternal.kts")
            appendLine("standalone=false")
            appendLine("jvmTarget=$SCRIPT_JVM_TARGET")
            appendLine("defaultImports=$importDigest")
            classpathDigests.forEach { (path, digest) ->
                append(path).append('=').appendLine(digest)
            }
        })

        val compilationConfiguration = ScriptCompilationConfiguration(ScriptCompilerConfig) {
            hostConfiguration(ScriptingHostConfig)
            isStandalone(false)
            jvm {
                updateClasspath(classpath)
            }
        }
        val runtimeDependencies = RuntimeDependencies(
            RuntimeDependencyClassLoader(
                runtimeLibraryClasspath.map { file -> file.toURI().toURL() }.toTypedArray(),
                snapshot.baseClassLoader,
                snapshot.pluginClassLoaders.filterNot { loader -> loader === snapshot.baseClassLoader }
            )
        )

        return ScriptCompilationEnvironment(
            compilationConfiguration,
            snapshot.baseClassLoader,
            runtimeDependencies.classLoader,
            classpath,
            imports,
            fingerprint,
            runtimeDependencies,
            snapshot.pluginClassLoaders.toList()
        )
    }

    private fun digestPath(path: Path): String = Sha256.digest { digest ->
        if (Files.isRegularFile(path)) {
            Sha256.update(digest, path)
        } else if (Files.isDirectory(path)) {
            Files.walk(path).use { paths ->
                paths.filter(Files::isRegularFile)
                    .sorted()
                    .forEach { file ->
                        digest.update(path.relativize(file).invariantSeparatorsPathString.toByteArray(Charsets.UTF_8))
                        digest.update(0.toByte())
                        Sha256.update(digest, file)
                    }
            }
        }
    }

    private fun codeSourceFile(type: Class<*>): File? = runCatching {
        File(type.protectionDomain.codeSource.location.toURI())
    }.getOrNull()
}

internal fun sourceChainDigest(sources: List<SharedReplSource>): String = Sha256.text(buildString {
    sources.forEach { source ->
        append(source.name).append('\u0000').append(source.hash).append('\n')
    }
})
