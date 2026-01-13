package eternalScript.core.script.definition

import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.manager.ConfigManager
import eternalScript.core.script.Script
import eternalScript.core.the.Root
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.util.classpathFromClassloader

fun pluginClasspath() = Root.plugins().flatMap { plugin ->
    classpathFromClassloader(plugin.javaClass.classLoader) ?: emptyList()
}

fun libraryClasspath() = ConfigManager.value<List<String>>(Config.LIBS).flatMap { lib ->
    Resource.PLUGINS.child(lib).searchAllSequence({ it.extension == "jar" })
}

private val classpath: List<File> by lazy {
    buildSet {
        addAll(pluginClasspath())
        addAll(libraryClasspath())
    }.toList()
}

fun ScriptCompilationConfiguration.Builder.importClasspath(list: List<File>) {
    val imports = buildSet {
        list.forEach { file ->
            JarFile(file).use { jar ->
                val names = jar.entries().asSequence()
                    .map(JarEntry::getRealName)
                    .filter { it.endsWith(".class") }
                    .filter { !it.startsWith("META-INF") }
                    .filter { !it.contains("package-info") }
                    .filter { !it.contains("module-info") }
                    .map { it.substringBeforeLast(".") }
                    .map { it.substringBefore("$") }
                    .map { it.replace("/", ".") }
                    .distinct()

                addAll(names)
            }
        }
    }

    defaultImports.append(imports)
}

object ScriptCompilerConfig : ScriptCompilationConfiguration({
    baseClass(Script::class)

    isStandalone(false)

    importClasspath(classpath)

    jvm {
        updateClasspath(classpath)
        compilerOptions.append("-jvm-target=21")
    }

    refineConfiguration {
        onAnnotations<Import>(ScriptImportHandler)
    }
})