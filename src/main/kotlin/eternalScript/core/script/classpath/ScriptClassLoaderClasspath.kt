package eternalScript.core.script.classpath

import eternalScript.core.script.definition.toClasspathFile
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Returns URLs owned by this loader and by embedded delegate loaders.
 *
 * Paper keeps Maven plugin libraries in a private URLClassLoader delegate
 * instead of the plugin loader's parent chain. Inspecting ClassLoader-typed
 * instance fields keeps the workspace/compiler classpath aligned with the
 * loader that Paper actually uses without depending on Paper implementation
 * class names.
 */
internal fun ClassLoader.embeddedClasspathFiles(): List<File> {
    val files = linkedMapOf<String, File>()
    ownedClassLoaders().forEach { loader ->
        if (loader !is URLClassLoader) return@forEach
        loader.getURLs()
            .mapNotNull { url -> url.toClasspathFile() }
            .forEach { file ->
                val normalized = file.toPath().toAbsolutePath().normalize().toFile()
                if (normalized.exists()) {
                    files.putIfAbsent(normalized.normalizedClasspathKey(), normalized)
                }
            }
    }
    return files.values.toList()
}

/**
 * Returns the plugin loader itself plus private loader delegates that define
 * classes on its behalf. Parent loaders are intentionally not traversed.
 */
internal fun ClassLoader.ownedClassLoaders(): List<ClassLoader> {
    val queue = ArrayDeque<ClassLoader>()
    val visited = IdentityHashMap<ClassLoader, Unit>()
    val loaders = mutableListOf<ClassLoader>()
    queue += this

    while (queue.isNotEmpty()) {
        val loader = queue.removeFirst()
        if (visited.put(loader, Unit) != null) continue
        loaders += loader
        loader.embeddedClassLoaders().forEach(queue::addLast)
    }
    return loaders
}

private fun ClassLoader.embeddedClassLoaders(): List<ClassLoader> = buildList {
    var type: Class<*>? = this@embeddedClassLoaders.javaClass
    while (type != null && type != ClassLoader::class.java && type != Any::class.java) {
        type.declaredFields
            .asSequence()
            .filterNot { field -> Modifier.isStatic(field.modifiers) }
            .filter { field -> ClassLoader::class.java.isAssignableFrom(field.type) }
            .mapNotNull { field ->
                runCatching {
                    if (!field.trySetAccessible()) return@runCatching null
                    field.get(this@embeddedClassLoaders) as? ClassLoader
                }.getOrNull()
            }
            .filterNot { loader -> loader === this@embeddedClassLoaders }
            .forEach(::add)
        type = type.superclass
    }
}

private fun File.normalizedClasspathKey(): String =
    toPath().toAbsolutePath().normalize().toString().replace(File.separatorChar, '/')
