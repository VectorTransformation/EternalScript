package eternalScript.core.script.classloading

import java.util.Collections
import java.util.WeakHashMap

/** Tracks live generation loaders for plugin invalidation. */
internal class ScriptGenerationRegistry {
    private val generations = Collections.synchronizedMap(
        WeakHashMap<ScriptGenerationClassLoader, Unit>()
    )

    fun invalidate(pluginName: String) {
        val generationLoaders = synchronized(generations) {
            generations.keys.toList()
        }
        generationLoaders.forEach { classLoader ->
            classLoader.invalidatePlugin(pluginName)
        }
    }

    internal fun register(classLoader: ScriptGenerationClassLoader) {
        synchronized(generations) {
            check(generations.put(classLoader, Unit) == null) {
                "A generation class loader is already registered."
            }
        }
    }

    internal fun unregister(classLoader: ScriptGenerationClassLoader) {
        synchronized(generations) {
            generations.remove(classLoader)
        }
    }

    fun clear() {
        synchronized(generations) {
            generations.clear()
        }
    }
}
