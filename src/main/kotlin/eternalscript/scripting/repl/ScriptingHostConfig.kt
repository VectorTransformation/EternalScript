package eternalscript.scripting.repl

import eternalscript.api.script.Script
import kotlin.reflect.KClass
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.getScriptingClass
import kotlin.script.experimental.jvm.GetScriptingClassByClassLoader
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.jvm

private class PluginGetScriptingClass : GetScriptingClassByClassLoader {
    override fun invoke(
        classType: KotlinType,
        contextClass: KClass<*>,
        hostConfiguration: ScriptingHostConfiguration
    ): KClass<*> {
        return invoke(classType, contextClass.java.classLoader, hostConfiguration)
    }

    override fun invoke(
        classType: KotlinType,
        contextClassLoader: ClassLoader?,
        hostConfiguration: ScriptingHostConfiguration
    ): KClass<*> {
        classType.fromClass?.let { return it }

        val classLoaders = listOfNotNull(
            Script::class.java.classLoader,
            contextClassLoader,
            Thread.currentThread().contextClassLoader
        ).distinct()

        for (classLoader in classLoaders) {
            try {
                return classLoader.loadClass(classType.typeName).kotlin
            } catch (_: ClassNotFoundException) {
                // Try the next loader in the Paper plugin/library loader chain.
            }
        }

        throw IllegalArgumentException("Unable to load scripting class ${classType.typeName}")
    }
}

/**
 * The K2 component compiler needs the JVM host defaults, especially the
 * scripting-class resolver. Artifact caching is owned by the v5 component cache.
 */
internal object ScriptingHostConfig : ScriptingHostConfiguration(
    defaultJvmScriptingHostConfiguration,
    body = {
        getScriptingClass(PluginGetScriptingClass())
        jvm {
            baseClassLoader(Script::class.java.classLoader)
        }
    }
)
