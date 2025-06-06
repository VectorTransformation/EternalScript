package eternalScript.definition

import eternalScript.data.Resource
import org.bukkit.Bukkit
import java.io.File
import java.util.jar.JarFile
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.util.classpathFromClassloader

private val pluginClassPath = Bukkit.getPluginManager().plugins.flatMap { plugin ->
    classpathFromClassloader(plugin.javaClass.classLoader) ?: emptyList()
}
private val libraryClassPath = Resource.LIBS.searchAllSequence(
    { it.extension == "jar" }
)
private val classPath = pluginClassPath + libraryClassPath

fun ScriptCompilationConfiguration.Builder.import(list: Collection<String>) {
    defaultImports.append(list)
}

fun ScriptCompilationConfiguration.Builder.importClassPath(list: List<File>) {
    val set = mutableSetOf<String>()
    list.forEach { file ->
        JarFile(file).use { jar ->
            val list = jar.entries().asSequence()
                .map { jarEntry ->
                    jarEntry.realName
                }.mapNotNull { realName ->
                    if (realName.startsWith("META-INF")) return@mapNotNull null
                    if (!realName.endsWith(".class")) return@mapNotNull null
                    val name = realName
                        .substringBeforeLast("/", "")
                        .replace("/", ".")
                    "$name.*"
                }.distinct()
            set.addAll(list)
        }
    }
    import(set)
}

class ScriptCompilerConfig : ScriptCompilationConfiguration({
    jvm {
        importClassPath(classPath)

        updateClasspath(classPath)

        compilerOptions.append("-jvm-target=21")
    }
})