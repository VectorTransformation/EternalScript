package eternalScript.core.script.project

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

/**
 * Build-time verifier for workspace, bundled runtime, and ignored example
 * projects. It uses the same ordinary Kotlin module backend as the runtime.
 */
object ScriptProjectCheckTool {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) {
            "Expected: <script-source-root> <compiler-cache-root> <runtime|ignored|all>"
        }
        val sourceRoot = Path.of(args[0]).toAbsolutePath().normalize()
        val cacheRoot = Path.of(args[1]).toAbsolutePath().normalize()
        val mode = ScriptProjectCheckMode.valueOf(args[2].uppercase())
        val files = sourceRoot.projectFiles(mode)
        if (files.isEmpty()) {
            println("OK EternalScript project (0 Kotlin sources)")
            return
        }

        val project = ScriptProjectSource.compose(files)
        val classpath = System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter(String::isNotBlank)
            .map(Path::of)
            .filter(Files::exists)
            .toList()
        val compiler = KotlinIncrementalProjectCompiler(
            cacheRoot = cacheRoot,
            classpath = classpath,
            implementationClassLoader = ScriptProjectCheckTool::class.java.classLoader
        )
        val compilation = compiler.compile(project.module)
        compilation.diagnostics.forEach { diagnostic ->
            val location = buildString {
                append(diagnostic.sourceName ?: PROJECT_SCRIPT_NAME)
                diagnostic.line?.let { line ->
                    append(':').append(line)
                    diagnostic.column?.let { column ->
                        append(':').append(column)
                    }
                }
            }
            println("${diagnostic.severity}: $location ${diagnostic.message}")
        }
        compilation.generationJar?.let { artifact ->
            compiler.pruneCaches(retainedGenerationJars = setOf(artifact))
        }
        if (!compilation.isSuccess) {
            exitProcess(1)
        }
        println("OK EternalScript project (${project.files.size} Kotlin sources)")
    }
}

private enum class ScriptProjectCheckMode {
    RUNTIME,
    IGNORED,
    ALL
}

private fun Path.projectFiles(
    mode: ScriptProjectCheckMode
): List<ScriptProjectFile> =
    Files.walk(this).use { paths ->
        paths
            .filter(Path::isRegularFile)
            .filter { path ->
                path.fileName.toString().endsWith(".kt")
            }
            .map { path ->
                val relative = relativize(path).toString().replace('\\', '/')
                relative to path
            }
            .filter { (relative, _) ->
                when (mode) {
                    ScriptProjectCheckMode.RUNTIME -> isRuntimeScriptPath(relative)
                    ScriptProjectCheckMode.IGNORED -> !isRuntimeScriptPath(relative)
                    ScriptProjectCheckMode.ALL -> true
                }
            }
            .map { (relative, path) ->
                ScriptProjectFile(
                    relative,
                    Files.readString(path, StandardCharsets.UTF_8)
                )
            }
            .toList()
    }
