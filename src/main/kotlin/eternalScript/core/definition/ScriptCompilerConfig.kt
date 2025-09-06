package eternalScript.core.definition

import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.manager.ConfigManager
import org.bukkit.Bukkit
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.refineConfiguration
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.util.classpathFromClassloader

private val pluginClassPath = Bukkit.getPluginManager().plugins.flatMap { plugin ->
    classpathFromClassloader(plugin.javaClass.classLoader) ?: emptyList()
}
private val libraryClassPath = ConfigManager.value<List<String>>(Config.LIBS).flatMap { lib ->
    Resource.PLUGINS.child(lib).searchAllSequence(
        { it.extension == "jar" }
    )
}
private val classPath = pluginClassPath + libraryClassPath

fun ScriptCompilationConfiguration.Builder.importClassPath(list: List<File>) {
    val set = mutableSetOf<String>()
    list.forEach { file ->
        JarFile(file).use { jar ->
            val list = jar.entries().asSequence()
                .map(JarEntry::getRealName)
                .mapNotNull { realName ->
                    if (realName.startsWith("META-INF")) return@mapNotNull null
                    if (realName.startsWith("module-info")) return@mapNotNull null
                    if (!realName.endsWith(".class")) return@mapNotNull null

                    val name = realName
                        .substringBeforeLast("/", "")
                        .replace("/", ".")

                    if (name.isEmpty()) return@mapNotNull realName.removeSuffix(".class")

                    "$name.*"
                }.distinct()
            set.addAll(list)
        }
    }
    defaultImports.append(set)
}

class ScriptCompilerConfig : ScriptCompilationConfiguration({
    jvm {
        updateClasspath(classPath)

        importClassPath(classPath)

        compilerOptions.append("-jvm-target=21")
    }

    refineConfiguration {
        onAnnotations(Import::class, handler = ScriptImportHandler())
    }
})