package eternalScript.core.script.classpath

import eternalScript.core.script.definition.coreClasspath
import eternalScript.core.script.definition.libraryClasspath
import eternalScript.core.script.definition.runtimeClasspathFingerprint
import eternalScript.core.script.definition.toClasspathFile
import eternalScript.core.the.Root
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight view of the enabled plugin set. Capturing this object touches
 * Bukkit plugin state but does not read or hash classpath contents.
 */
internal data class ScriptPluginClasspathCapture(
    internal val revision: Long,
    internal val parentClassLoader: ClassLoader,
    internal val coreFiles: List<File>,
    internal val plugins: List<CapturedScriptPlugin>
)

internal data class CapturedScriptPlugin(
    val name: String,
    val version: String,
    internal val classLoader: ClassLoader,
    internal val files: List<File>
)

/**
 * Immutable plugin/classpath roster used by one compiler and runtime
 * generation. The conflict log is intentionally generation-scoped.
 */
internal class ScriptPluginClasspathSnapshot internal constructor(
    val parentClassLoader: ClassLoader,
    val coreFiles: List<File>,
    val libraryFiles: List<File>,
    val plugins: List<ScriptPluginClasspathPlugin>,
    val files: List<File>,
    val fingerprint: String,
    private val conflictLog: ScriptClassConflictLog = ScriptClassConflictLog()
) {
    val conflicts: List<ScriptClassIdentityConflict>
        get() = conflictLog.snapshot()

    internal val pluginFiles: List<File> = plugins
        .flatMap(ScriptPluginClasspathPlugin::files)
        .distinctBy(File::normalizedClasspathKey)

    internal fun resolvePluginClass(className: String): ResolvedPluginClass? =
        PluginClassResolver(parentClassLoader, plugins, conflictLog).resolve(className)

    internal fun ownerNamesForResolvedClass(
        className: String,
        type: Class<*>
    ): Set<String> {
        val providers = plugins.mapNotNull { plugin ->
            try {
                plugin.takeIf { plugin.classLoader.loadClass(className) === type }
            } catch (_: ClassNotFoundException) {
                null
            }
        }
        val definingOwners = providers
            .filter { plugin -> plugin.classLoader === type.classLoader }
            .mapTo(
                sortedSetOf(String.CASE_INSENSITIVE_ORDER),
                ScriptPluginClasspathPlugin::name
            )
        if (definingOwners.isNotEmpty()) return definingOwners

        val parentOwnsIdentity = try {
            parentClassLoader.loadClass(className) === type
        } catch (_: ClassNotFoundException) {
            false
        }
        return if (parentOwnsIdentity) {
            emptySet()
        } else {
            providers.mapTo(
                sortedSetOf(String.CASE_INSENSITIVE_ORDER),
                ScriptPluginClasspathPlugin::name
            )
        }
    }

    internal fun withoutPlugin(pluginName: String): ScriptPluginClasspathSnapshot {
        val remainingPlugins = plugins.filterNot { plugin ->
            plugin.name.equals(pluginName, ignoreCase = true)
        }
        if (remainingPlugins.size == plugins.size) return this
        val retainedPaths = buildSet {
            coreFiles.mapTo(this) { file -> file.normalizedClasspathKey() }
            libraryFiles.mapTo(this) { file -> file.normalizedClasspathKey() }
            remainingPlugins.forEach { plugin ->
                plugin.files.mapTo(this) { file -> file.normalizedClasspathKey() }
            }
        }
        return ScriptPluginClasspathSnapshot(
            parentClassLoader = parentClassLoader,
            coreFiles = coreFiles,
            libraryFiles = libraryFiles,
            plugins = remainingPlugins,
            files = files.filter { file -> file.normalizedClasspathKey() in retainedPaths },
            fingerprint = fingerprint,
            conflictLog = conflictLog
        )
    }
}

internal data class ScriptPluginClasspathPlugin(
    val name: String,
    val version: String,
    val files: List<File>,
    internal val classLoader: ClassLoader
)

internal data class ScriptClassIdentityProvider(
    val pluginName: String,
    val pluginVersion: String,
    val classLoaderDescription: String
)

internal data class ScriptClassIdentityConflict(
    val className: String,
    val providers: List<ScriptClassIdentityProvider>
) {
    val message: String = buildString {
        append("Class identity conflict for ")
        append(className)
        append(": ")
        append(
            providers.joinToString { provider ->
                "${provider.pluginName} ${provider.pluginVersion} " +
                    "(${provider.classLoaderDescription})"
            }
        )
    }
}

internal class ScriptClassIdentityConflictException(
    val conflicts: List<ScriptClassIdentityConflict>
) : LinkageError(
    conflicts.joinToString(
        prefix = "Script plugin class identity conflict(s): ",
        separator = "; "
    ) { conflict -> conflict.message }
) {
    constructor(conflict: ScriptClassIdentityConflict) : this(listOf(conflict))
}

internal data class ResolvedPluginClass(
    val type: Class<*>,
    val ownerNames: Set<String>
)

internal object ScriptPluginClasspathRegistry {
    private data class PublishedSnapshot(
        val revision: Long,
        val snapshot: ScriptPluginClasspathSnapshot
    )

    private val nextRevision = AtomicLong()
    private val minimumPublishRevision = AtomicLong()
    private val published = AtomicReference<PublishedSnapshot?>()

    /**
     * Must be called while Bukkit's plugin collection is safe to inspect.
     * No JAR contents are read here.
     */
    fun capture(plugins: Iterable<Plugin>): ScriptPluginClasspathCapture {
        val enabled = plugins
            .asSequence()
            .filter(Plugin::isEnabled)
            .sortedWith(
                compareBy<Plugin> { plugin -> plugin.name.lowercase(Locale.ROOT) }
                    .thenBy(Plugin::getName)
            )
            .toList()
        val parent = enabled
            .firstOrNull { plugin -> plugin.name == Root.ORIGIN }
            ?.javaClass
            ?.classLoader
            ?: Root.classLoader(Root.ORIGIN)
            ?: ScriptPluginClasspathRegistry::class.java.classLoader
        val capturedPlugins = enabled.map { plugin ->
            val loader = plugin.javaClass.classLoader
            CapturedScriptPlugin(
                name = plugin.name,
                version = plugin.pluginMeta.version,
                classLoader = loader,
                files = loader.ownClasspathFiles(plugin.javaClass)
            )
        }
        return ScriptPluginClasspathCapture(
            revision = nextRevision.incrementAndGet(),
            parentClassLoader = parent,
            coreFiles = coreClasspath(parent),
            plugins = capturedPlugins
        )
    }

    /**
     * Builds and publishes a snapshot. This reads and hashes classpath files,
     * so callers should invoke it away from the server/global tick thread.
     */
    fun refresh(
        capture: ScriptPluginClasspathCapture
    ): ScriptPluginClasspathSnapshot {
        requirePublishable(capture)
        val libraries = libraryClasspath()
        requirePublishable(capture)
        return refresh(capture, libraries)
    }

    internal fun refresh(
        capture: ScriptPluginClasspathCapture,
        libraryFiles: Iterable<File>
    ): ScriptPluginClasspathSnapshot {
        requirePublishable(capture)
        val snapshot = buildSnapshot(capture, libraryFiles)
        requirePublishable(capture)
        while (true) {
            requirePublishable(capture)
            val previous = published.get()
            if (previous != null && previous.revision > capture.revision) {
                requirePublishable(capture)
                return previous.snapshot
            }
            val candidate = PublishedSnapshot(capture.revision, snapshot)
            if (published.compareAndSet(previous, candidate)) {
                try {
                    requirePublishable(capture)
                    return snapshot
                } catch (exception: CancellationException) {
                    published.compareAndSet(candidate, null)
                    throw exception
                }
            }
        }
    }

    /**
     * Convenience path for callers that do not need to split Bukkit capture
     * from filesystem hashing.
     */
    fun refresh(plugins: Iterable<Plugin>): ScriptPluginClasspathSnapshot =
        refresh(capture(plugins))

    fun current(): ScriptPluginClasspathSnapshot? = published.get()?.snapshot

    fun requireCurrent(): ScriptPluginClasspathSnapshot =
        current() ?: throw IllegalStateException(
            "Script plugin classpath is not prepared. Capture and refresh enabled plugins " +
                "after ServerLoadEvent before compiling scripts or reading workspace metadata."
        )

    fun clear() {
        minimumPublishRevision.set(nextRevision.incrementAndGet())
        published.set(null)
    }

    private fun requirePublishable(capture: ScriptPluginClasspathCapture) {
        if (capture.revision <= minimumPublishRevision.get()) {
            throw CancellationException(
                "Discarded stale script plugin classpath capture revision ${capture.revision}."
            )
        }
    }

    internal fun buildSnapshot(
        capture: ScriptPluginClasspathCapture,
        libraryFiles: Iterable<File>
    ): ScriptPluginClasspathSnapshot {
        val core = capture.coreFiles.normalizedDistinct()
        val libraries = libraryFiles.normalizedDistinct()
            .sortedBy(File::normalizedClasspathKey)
        val plugins = capture.plugins
            .sortedWith(
                compareBy<CapturedScriptPlugin> { plugin -> plugin.name.lowercase(Locale.ROOT) }
                    .thenBy(CapturedScriptPlugin::name)
            )
            .map { plugin ->
                ScriptPluginClasspathPlugin(
                    name = plugin.name,
                    version = plugin.version,
                    files = plugin.files.normalizedDistinct(),
                    classLoader = plugin.classLoader
                )
            }
        val files = buildList {
            addAll(core)
            plugins.forEach { plugin -> addAll(plugin.files) }
            addAll(libraries)
        }.distinctBy(File::normalizedClasspathKey)
        val rosterFields = buildList {
            plugins.forEach { plugin ->
                add(plugin.name)
                add(plugin.version)
                add(plugin.classLoader.javaClass.name)
                plugin.files.forEach { file -> add(file.normalizedClasspathKey()) }
            }
        }
        return ScriptPluginClasspathSnapshot(
            parentClassLoader = capture.parentClassLoader,
            coreFiles = core,
            libraryFiles = libraries,
            plugins = plugins,
            files = files,
            fingerprint = runtimeClasspathFingerprint(files, rosterFields)
        )
    }

    internal fun resetForTests() {
        published.set(null)
        minimumPublishRevision.set(0)
        nextRevision.set(0)
    }
}

private class PluginClassResolver(
    private val parentClassLoader: ClassLoader,
    private val plugins: List<ScriptPluginClasspathPlugin>,
    private val conflictLog: ScriptClassConflictLog
) {
    fun resolve(className: String): ResolvedPluginClass? {
        val providers = plugins.mapNotNull { plugin ->
            try {
                plugin to plugin.classLoader.loadClass(className)
            } catch (_: ClassNotFoundException) {
                null
            }
        }
        if (providers.isEmpty()) return null

        val parentType = try {
            parentClassLoader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            null
        }
        val identities = IdentityHashMap<Class<*>, MutableList<ScriptPluginClasspathPlugin?>>()
        providers.forEach { (plugin, type) ->
            identities.getOrPut(type) { mutableListOf() }.add(plugin)
        }
        parentType?.let { type ->
            identities.getOrPut(type) { mutableListOf() }.add(null)
        }
        if (identities.size > 1) {
            if (isBenignDuplicateClass(className)) {
                val selectedType = parentType ?: providers.first().second
                val selectedOwners = providers
                    .asSequence()
                    .filter { (_, type) -> type.classLoader === selectedType.classLoader }
                    .mapTo(sortedSetOf(String.CASE_INSENSITIVE_ORDER)) { (plugin, _) ->
                        plugin.name
                    }
                return ResolvedPluginClass(selectedType, selectedOwners)
            }
            val conflict = ScriptClassIdentityConflict(
                className = className,
                providers = buildList {
                    providers.mapTo(this) { (plugin, type) ->
                        ScriptClassIdentityProvider(
                            pluginName = plugin.name,
                            pluginVersion = plugin.version,
                            classLoaderDescription = type.classLoader.description()
                        )
                    }
                    parentType?.let { type ->
                        add(
                            ScriptClassIdentityProvider(
                                pluginName = "<runtime-parent>",
                                pluginVersion = "current",
                                classLoaderDescription = type.classLoader.description()
                            )
                        )
                    }
                }.distinct()
            )
            conflictLog.record(conflict)
            throw ScriptClassIdentityConflictException(conflict)
        }

        val type = identities.keys.single()
        val definingOwners = providers
            .asSequence()
            .map(Pair<ScriptPluginClasspathPlugin, Class<*>>::first)
            .filter { plugin -> plugin.classLoader === type.classLoader }
            .map(ScriptPluginClasspathPlugin::name)
            .toCollection(sortedSetOf(String.CASE_INSENSITIVE_ORDER))
        if (definingOwners.isNotEmpty()) {
            return ResolvedPluginClass(type, definingOwners)
        }

        val parentOwnsIdentity = parentType === type
        val owners = if (parentOwnsIdentity) {
            emptySet()
        } else {
            providers
                .mapTo(sortedSetOf(String.CASE_INSENSITIVE_ORDER)) { (plugin, _) ->
                    plugin.name
                }
        }
        return ResolvedPluginClass(type, owners)
    }
}

internal class ScriptClassConflictLog {
    private val conflicts = CopyOnWriteArrayList<ScriptClassIdentityConflict>()

    fun record(conflict: ScriptClassIdentityConflict) {
        if (conflicts.none { existing -> existing == conflict }) {
            conflicts += conflict
        }
    }

    fun snapshot(): List<ScriptClassIdentityConflict> = conflicts.toList()
}

/** Kotlin and Java annotation metadata has no executable plugin identity. */
private fun isBenignDuplicateClass(className: String): Boolean =
    className.startsWith("org.jetbrains.annotations.")

private fun ClassLoader.ownClasspathFiles(mainClass: Class<*>): List<File> =
    buildList {
        addAll(this@ownClasspathFiles.embeddedClasspathFiles())
        mainClass.protectionDomain
            ?.codeSource
            ?.location
            ?.toClasspathFile()
            ?.let(::add)
    }.normalizedDistinct()

private fun ClassLoader?.description(): String = when (this) {
    null -> "bootstrap"
    else -> "${javaClass.name}@${Integer.toHexString(System.identityHashCode(this))}"
}

private fun Iterable<File>.normalizedDistinct(): List<File> =
    map { file -> file.toPath().toAbsolutePath().normalize().toFile() }
        .filter(File::exists)
        .distinctBy(File::normalizedClasspathKey)

private fun File.normalizedClasspathKey(): String =
    toPath().toAbsolutePath().normalize().toString().replace(File.separatorChar, '/')
