package eternalscript.scripting.cache

import eternalscript.api.script.Script
import eternalscript.scripting.repl.SCRIPT_JVM_TARGET
import eternalscript.scripting.repl.ScriptCompilerConfig
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.repl.k2.ComponentCompilationResult
import eternalscript.scripting.repl.k2.ComponentEvaluationResult
import eternalscript.scripting.repl.k2.ComponentGenerationEvaluator
import eternalscript.scripting.repl.k2.ScriptComponentCompiler
import eternalscript.scripting.runtime.ReplStateBridge
import java.nio.file.Files
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.isStandalone
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ComponentArtifactCacheTest {
    private val configuration = ScriptCompilationConfiguration(ScriptCompilerConfig) {
        isStandalone(false)
        jvm {
            dependenciesFromClassContext(Script::class, wholeClasspath = true)
            updateClasspath(
                listOf(java.io.File(Script::class.java.protectionDomain.codeSource.location.toURI()))
            )
            compilerOptions.append("-jvm-target=$SCRIPT_JVM_TARGET")
        }
    }
    private val environmentFingerprint = "component-cache-test-environment"

    @Test
    fun `round trips v5 component artifacts and classifiers without compilation`() {
        val root = Files.createTempDirectory("eternalscript-cache-v5")
        val compileRoot = root.resolve("compiled")
        val liveRoot = root.resolve("live")
        val cacheRoot = root.resolve("scripts-v5").toFile()
        val sources = listOf(
            SharedReplSource(
                "00-provider.eternal.kts",
                """
                    val base: Int = 40
                    fun plusBase(value: Int): Int = base + value
                    class ProviderType
                    typealias ProviderAlias = ProviderType
                """.trimIndent()
            ),
            SharedReplSource(
                "10-consumer.eternal.kts",
                "val result: Int = plusBase(2)"
            )
        )
        var cachedGeneration: eternalscript.scripting.repl.k2.CompiledComponentGeneration? = null
        var evaluatedGeneration: eternalscript.scripting.repl.k2.EvaluatedComponentGeneration? = null
        try {
            val compiled = assertIs<ComponentCompilationResult.Success>(
                ScriptComponentCompiler(configuration, compileRoot, Script::class.java.classLoader)
                    .compile(sources)
            ).generation
            val cache = ComponentArtifactCache(cacheRoot, liveRoot)
            val key = cache.publish(compiled, environmentFingerprint)
            compiled.close()

            val lookup = assertIs<ComponentCacheLookup.Hit>(cache.lookup(sources, environmentFingerprint))
            assertEquals(key, lookup.key)
            cachedGeneration = lookup.generation
            assertEquals(2, lookup.generation.components.size)
            assertEquals(
                setOf("ProviderType", "ProviderAlias"),
                lookup.generation.scripts.first().classifiers.map { classifier -> classifier.name }.toSet()
            )
            val evaluation = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    lookup.generation,
                    lookup.generation.graph.initializationOrder,
                    Script::class.java.classLoader
                )
            ).generation
            evaluatedGeneration = evaluation
            val consumer = evaluation.scripts.getValue("10-consumer.eternal.kts").instance
            assertEquals(42, consumer.javaClass.getMethod("getResult").invoke(consumer))

            evaluation.close()
            evaluatedGeneration = null
            lookup.generation.close()
            cachedGeneration = null
            val objectDirectory = cacheRoot.resolve("objects")
                .listFiles()
                .orEmpty()
                .single { file -> file.isDirectory && !file.name.startsWith('.') }
            val jar = objectDirectory.listFiles().orEmpty().first { file -> file.extension == "jar" }
            jar.writeBytes(byteArrayOf(1, 2, 3))
            assertIs<ComponentCacheLookup.Miss>(cache.lookup(sources, environmentFingerprint))
            assertIs<ComponentCacheLookup.Miss>(
                cache.lookup(sources, "different-environment")
            )

            val repaired = assertIs<ComponentCompilationResult.Success>(
                ScriptComponentCompiler(configuration, compileRoot, Script::class.java.classLoader)
                    .compile(sources)
            ).generation
            val interruptedPublish = cacheRoot.resolve("objects").resolve(".interrupted.tmp")
            interruptedPublish.mkdirs()
            interruptedPublish.resolve("partial.jar").writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(key, cache.publish(repaired, environmentFingerprint))
            assertFalse(interruptedPublish.exists())
            assertEquals(key, cache.publish(repaired, environmentFingerprint))
            repaired.close()
            val currentId = requireNotNull(
                Regex("\"current\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*\"([^\"]+)\"")
                    .find(cacheRoot.resolve("index.json").readText())
            ).groupValues[1]
            cacheRoot.resolve("objects").resolve(currentId).resolve("manifest.json").appendText(" ")
            val repairedLookup = assertIs<ComponentCacheLookup.Hit>(cache.lookup(sources, environmentFingerprint))
            repairedLookup.generation.close()
        } finally {
            evaluatedGeneration?.close()
            cachedGeneration?.close()
            root.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }
}
