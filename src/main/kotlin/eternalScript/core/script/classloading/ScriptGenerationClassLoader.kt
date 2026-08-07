package eternalScript.core.script.classloading

import eternalScript.core.script.classpath.ScriptPluginClasspathSnapshot
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads generated classes itself, resolves platform/core classes parent-first,
 * and delegates plugin API classes to the plugins' actual class loaders.
 */
internal class ScriptGenerationClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    classpathSnapshot: ScriptPluginClasspathSnapshot,
    private val ownedClassNames: Set<String>,
    initialPluginOwners: Set<String>
) : URLClassLoader(urls, parent) {
    internal val pluginDependencies = ScriptPluginDependencyTracker(initialPluginOwners)
    private val activeClasspathSnapshot = AtomicReference(classpathSnapshot)
    private val invalidatedPlugins = ConcurrentHashMap<String, InvalidatedPlugin>()
    private val pluginResolutionMonitor = Any()

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { loaded ->
                rejectInvalidatedPluginClass(name, loaded)
                if (resolve) resolveClass(loaded)
                return@synchronized loaded
            }

            val loaded = when {
                name in ownedClassNames -> findClass(name)
                isParentFirstScriptClass(name) -> loadParentFirst(name)
                else -> loadPluginLibraryOrParent(name)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

    private fun rejectInvalidatedPluginClass(name: String, type: Class<*>) {
        val disabledDefiners = invalidatedPlugins.values
            .filter { plugin -> plugin.owns(type.classLoader) }
            .map(InvalidatedPlugin::name)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (disabledDefiners.isNotEmpty()) {
            throw DisabledScriptPluginClassException(name, disabledDefiners)
        }
    }

    private fun loadParentFirst(name: String): Class<*> {
        val parentType = try {
            parent.loadClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }
        if (parentType != null) {
            rejectInvalidatedPluginClass(name, parentType)
            val snapshot = activeClasspathSnapshot.get()
            val owners = snapshot.ownerNamesForResolvedClass(name, parentType)
            snapshot.resolvePluginClass(name)
            pluginDependencies.addAll(owners)
            return parentType
        }
        // A plugin or configured library can provide an optional class beneath
        // a platform namespace that is absent from the parent.
        return loadPluginLibraryOrParent(name)
    }

    override fun getResource(name: String): URL? {
        parent.getResource(name)?.let { return it }
        synchronized(pluginResolutionMonitor) {
            val providers = activeClasspathSnapshot.get().plugins.mapNotNull { plugin ->
                plugin.classLoader.getResource(name)?.let { resource ->
                    plugin.name to resource
                }
            }
            providers.firstOrNull()?.let { (_, selected) ->
                pluginDependencies.addAll(
                    providers
                        .filter { (_, resource) -> resource == selected }
                        .map { (pluginName) -> pluginName }
                )
                return selected
            }
            return findResource(name)
        }
    }

    override fun getResources(name: String): java.util.Enumeration<URL> {
        val parentResources = linkedSetOf<URL>()
        parent.getResources(name).toList(parentResources)
        val resources = linkedSetOf<URL>().apply {
            addAll(parentResources)
        }
        synchronized(pluginResolutionMonitor) {
            activeClasspathSnapshot.get().plugins.forEach { plugin ->
                val provided = linkedSetOf<URL>()
                plugin.classLoader.getResources(name).toList(provided)
                if (provided.any { resource -> resource !in parentResources }) {
                    pluginDependencies.addAll(listOf(plugin.name))
                }
                resources.addAll(provided)
            }
            findResources(name).toList(resources)
        }
        return Collections.enumeration(resources)
    }

    private fun loadPluginLibraryOrParent(name: String): Class<*> =
        synchronized(pluginResolutionMonitor) {
            activeClasspathSnapshot.get().resolvePluginClass(name)?.let { resolved ->
                rejectInvalidatedPluginClass(name, resolved.type)
                pluginDependencies.addAll(resolved.ownerNames)
                return@synchronized resolved.type
            }

            val parentType = try {
                parent.loadClass(name)
            } catch (_: ClassNotFoundException) {
                null
            }
            if (parentType != null) {
                rejectInvalidatedPluginClass(name, parentType)
                return@synchronized parentType
            }
            // Fall through to configured libraries after the core parent.
            findClass(name)
        }

    internal fun invalidatePlugin(pluginName: String) {
        synchronized(pluginResolutionMonitor) {
            val snapshot = activeClasspathSnapshot.get()
            snapshot.plugins
                .filter { plugin -> plugin.name.equals(pluginName, ignoreCase = true) }
                .forEach { plugin ->
                    invalidatedPlugins[plugin.name.lowercase(Locale.ROOT)] =
                        InvalidatedPlugin(
                            plugin.name,
                            plugin.ownedClassLoaders.map { loader -> WeakReference(loader) }
                        )
                }
            activeClasspathSnapshot.set(snapshot.withoutPlugin(pluginName))
        }
    }

    companion object {
        init {
            registerAsParallelCapable()
        }
    }
}

private fun java.util.Enumeration<URL>.toList(destination: MutableSet<URL>) {
    while (hasMoreElements()) {
        destination += nextElement()
    }
}

private data class InvalidatedPlugin(
    val name: String,
    val classLoaders: List<WeakReference<ClassLoader>>
) {
    fun owns(loader: ClassLoader?): Boolean =
        classLoaders.any { reference -> reference.get() === loader }
}

internal class DisabledScriptPluginClassException(
    className: String,
    pluginNames: List<String>
) : ClassNotFoundException(
    "Class $className is no longer available because plugin(s) " +
        "${pluginNames.joinToString()} are disabled."
)

internal class ScriptPluginDependencyTracker(
    initialOwners: Set<String>
) {
    private val owners = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(initialOwners)
    }

    fun addAll(pluginNames: Iterable<String>) {
        owners.addAll(pluginNames)
    }

    fun snapshot(): Set<String> =
        owners.toSortedSet(String.CASE_INSENSITIVE_ORDER)
}
