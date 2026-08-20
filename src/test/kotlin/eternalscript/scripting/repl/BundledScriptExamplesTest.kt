package eternalscript.scripting.repl

import eternalscript.api.script.Script
import eternalscript.scripting.repl.k2.BatchCompilationResult
import eternalscript.scripting.repl.k2.BatchEvaluationResult
import eternalscript.scripting.repl.k2.BatchK2Compiler
import eternalscript.scripting.repl.k2.BatchK2Evaluator
import eternalscript.scripting.source.isEternalScriptFile
import java.io.File
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.isStandalone
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BundledScriptExamplesTest {
    private val compilationConfiguration = ScriptCompilationConfiguration(ScriptCompilerConfig) {
        isStandalone(false)
        jvm {
            dependenciesFromClassContext(Script::class, wholeClasspath = true)
        }
    }
    @Test
    fun `bundled scripts compile with curated defaults and bidirectional shared declarations`() {
        val root = File(requireNotNull(javaClass.classLoader.getResource("scripts")).toURI())
        val sources = root.walkTopDown()
            .filter(::isEternalScriptFile)
            .map { file ->
                SharedReplSource(
                    file.relativeTo(root).invariantSeparatorsPath,
                    file.readText(Charsets.UTF_8)
                )
            }
            .sortedWith(compareBy({ source -> source.name.lowercase() }, SharedReplSource::name))
            .toList()

        assertEquals(
            listOf(
                "-example/command.eternal.kts",
                "-example/event.eternal.kts",
                "-example/function.eternal.kts",
                "-example/function_use.eternal.kts",
                "-example/lifecycle.eternal.kts",
                "hello.eternal.kts"
            ),
            sources.map(SharedReplSource::name)
        )

        val result = BatchK2Compiler(compilationConfiguration).use { compiler -> compiler.compile(sources) }
        val generation = assertIs<BatchCompilationResult.Success>(
            result,
            (result as? BatchCompilationResult.Failure)?.diagnostic?.let { "${it.source}: ${it.message}" }
        ).generation
        val sharedComponent = generation.graph.componentOf("-example/function.eternal.kts")
        assertEquals(
            setOf("-example/function.eternal.kts", "-example/function_use.eternal.kts"),
            sharedComponent?.paths?.toSet()
        )
        assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
    }
}
