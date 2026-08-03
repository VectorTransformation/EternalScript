package eternalScript.core.script.project

import eternalScript.core.script.generation.ScriptProjectCheckOutcome
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
        require(args.size == 4) {
            "Expected: <script-source-root> <compiler-cache-root> " +
                "<runtime|ignored|all> <require-sources|allow-empty>"
        }
        val sourceRoot = Path.of(args[0]).toAbsolutePath().normalize()
        val cacheRoot = Path.of(args[1]).toAbsolutePath().normalize()
        val mode = ScriptProjectCheckMode.valueOf(args[2].uppercase())
        val emptyPolicy = ScriptProjectEmptyPolicy.valueOf(
            args[3].replace('-', '_').uppercase()
        )
        val files = sourceRoot.projectFiles(mode)
        if (files.isEmpty()) {
            if (emptyPolicy == ScriptProjectEmptyPolicy.ALLOW_EMPTY) {
                ScriptProjectCheckCliPresenter.allowedEmptyLines().forEach(::println)
                return
            }
            ScriptProjectCheckCliPresenter.lines(
                ScriptProjectCheckCliSummary(
                    outcome = ScriptProjectCheckOutcome.NO_SOURCES,
                    sourceCount = 0,
                    diagnosticCount = 0
                )
            ).forEach(::println)
            exitProcess(ScriptProjectCheckCliPresenter.NO_SOURCES_EXIT_CODE)
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
        val summary = ScriptProjectCheckCliSummary(
            outcome = if (compilation.isSuccess) {
                ScriptProjectCheckOutcome.PASSED
            } else {
                ScriptProjectCheckOutcome.FAILED
            },
            sourceCount = project.files.size,
            diagnosticCount = compilation.diagnostics.count { it.isError }
        )
        ScriptProjectCheckCliPresenter.lines(summary).forEach(::println)
        if (!compilation.isSuccess) {
            exitProcess(ScriptProjectCheckCliPresenter.FAILED_EXIT_CODE)
        }
    }
}

internal data class ScriptProjectCheckCliSummary(
    val outcome: ScriptProjectCheckOutcome,
    val sourceCount: Int,
    val diagnosticCount: Int
)

/** Stable English terminal presentation for generated Gradle workspaces. */
internal object ScriptProjectCheckCliPresenter {
    const val FAILED_EXIT_CODE = 1
    const val NO_SOURCES_EXIT_CODE = 2

    fun allowedEmptyLines(): List<String> = listOf(
        "NO_SOURCES EternalScript project | sources=0 | diagnostics=0 | allowed=true",
        "No action is required for this repository verification target."
    )

    fun lines(summary: ScriptProjectCheckCliSummary): List<String> =
        when (summary.outcome) {
            ScriptProjectCheckOutcome.NO_SOURCES -> listOf(
                "NO_SOURCES EternalScript project | sources=0 | diagnostics=0",
                "Next: copy scripts/-examples/hello.kt to scripts/hello.kt, then run gradle checkScripts again."
            )
            ScriptProjectCheckOutcome.PASSED -> listOf(
                "PASSED EternalScript project | sources=${summary.sourceCount} | " +
                    "diagnostics=${summary.diagnosticCount}",
                "Next: run /es reload on the server to activate this project."
            )
            ScriptProjectCheckOutcome.FAILED -> listOf(
                "FAILED EternalScript project | sources=${summary.sourceCount} | " +
                    "diagnostics=${summary.diagnosticCount}",
                "Next: fix the diagnostics, then run gradle checkScripts again."
            )
        }
}

private enum class ScriptProjectCheckMode {
    RUNTIME,
    IGNORED,
    ALL
}

private enum class ScriptProjectEmptyPolicy {
    REQUIRE_SOURCES,
    ALLOW_EMPTY
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
