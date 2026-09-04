package eternalscript.scripting.repl.k2

import eternalscript.api.script.Script
import eternalscript.logging.EternalLogLevel
import eternalscript.logging.OperationalLogSink
import eternalscript.logging.ScriptLoggingRuntime
import eternalscript.logging.UnifiedLoggingService
import eternalscript.scripting.repl.SCRIPT_JVM_TARGET
import eternalscript.scripting.repl.ScriptCompilerConfig
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.compilation.RuntimeDependencyClassLoader
import eternalscript.scripting.runtime.ReplStateBridge
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.isStandalone
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.withUpdatedClasspath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScriptComponentCompilerTest {
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

    @Test
    fun `script logging has logical path during top level and lifecycle callbacks`() {
        val temp = Files.createTempDirectory("eternalscript-script-logging")
        val messages = mutableListOf<String>()
        val logging = UnifiedLoggingService(
            { EternalLogLevel.DEBUG },
            { 500L },
            object : OperationalLogSink {
                override fun write(
                    level: EternalLogLevel,
                    message: Component,
                    cause: Throwable?
                ) {
                    messages += (message as TextComponent).content()
                }
            }
        )
        val registration = ScriptLoggingRuntime.install(logging)
        var compiledGeneration: CompiledComponentGeneration? = null
        var evaluatedGeneration: EvaluatedComponentGeneration? = null
        try {
            val result = ScriptComponentCompiler(
                configuration,
                temp,
                Script::class.java.classLoader
            ).compile(
                listOf(
                    SharedReplSource(
                        "logging/lifecycle.eternal.kts",
                        """
                        log.info("top")
                        onLoad { log.info("load") }
                        onUnload { log.info("unload") }
                        onDispose { log.info("dispose") }
                        """.trimIndent()
                    )
                )
            )
            val compiled = assertIs<ComponentCompilationResult.Success>(result)
            compiledGeneration = compiled.generation
            val evaluated = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    compiled.generation,
                    compiled.affectedPaths,
                    Script::class.java.classLoader
                )
            ).generation
            evaluatedGeneration = evaluated
            val script = evaluated.scripts.getValue("logging/lifecycle.eternal.kts").script
            val declarations = script.freezeDeclarations()

            declarations.loadCallbacks.single().invoke()
            declarations.unloadCallbacks.single().invoke()
            script.disposeDeclarations()

            assertEquals(
                listOf("top", "load", "unload", "dispose").map { message ->
                    "[script:logging/lifecycle.eternal.kts] $message"
                },
                messages
            )
        } finally {
            evaluatedGeneration?.close()
            compiledGeneration?.close()
            registration.close()
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `graph analysis resolves an inferred later provider used by a lifecycle callback`() {
        val temp = Files.createTempDirectory("eternalscript-inferred-lifecycle")
        val property = "eternalscript.k2.component-inferred-peer"
        var compiledGeneration: CompiledComponentGeneration? = null
        var evaluatedGeneration: EvaluatedComponentGeneration? = null
        var previousState: ReplStateBridge.StateTable? = null
        try {
            val result = ScriptComponentCompiler(
                configuration,
                temp,
                Script::class.java.classLoader
            ).compile(
                listOf(
                    SharedReplSource(
                        "00-consumer.eternal.kts",
                        "onLoad { System.setProperty(\"$property\", \"value=${'$'}a\") }"
                    ),
                    SharedReplSource("10-provider.eternal.kts", "val a = \"a\"")
                )
            )
            val compiled = assertIs<ComponentCompilationResult.Success>(
                result,
                (result as? ComponentCompilationResult.Failure)?.diagnostic?.let { diagnostic ->
                    "${diagnostic.source}: ${diagnostic.message}"
                }
            )
            compiledGeneration = compiled.generation
            assertEquals(
                setOf("10-provider.eternal.kts"),
                compiled.generation.graph.dependencies.getValue("00-consumer.eternal.kts")
            )

            val evaluated = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    compiled.generation,
                    compiled.affectedPaths,
                    Script::class.java.classLoader
                )
            ).generation
            evaluatedGeneration = evaluated
            previousState = ReplStateBridge.publish(evaluated.state)
            evaluated.scripts
                .getValue("00-consumer.eternal.kts")
                .script
                .freezeDeclarations()
                .loadCallbacks
                .single()
                .invoke()
            assertEquals("value=a", System.getProperty(property))
        } finally {
            previousState?.let(ReplStateBridge::publish)
            System.clearProperty(property)
            evaluatedGeneration?.close()
            compiledGeneration?.close()
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `recompiles only a changed SCC and its reverse dependents`() {
        val temp = Files.createTempDirectory("eternalscript-components")
        var compiledGeneration: CompiledComponentGeneration? = null
        var evaluatedGeneration: EvaluatedComponentGeneration? = null
        try {
            val compiler = ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader)
            val coldSources = sources(base = 40, independent = 7)
            val coldResult = compiler.compile(coldSources)
            val cold = assertIs<ComponentCompilationResult.Success>(
                coldResult,
                (coldResult as? ComponentCompilationResult.Failure)?.diagnostic?.let { diagnostic ->
                    "${diagnostic.source}: ${diagnostic.message}\n${diagnostic.cause?.stackTraceToString()}"
                }
            )
            assertEquals(3, cold.metrics.analyzed)
            assertEquals(3, cold.metrics.compiled)
            assertEquals(0, cold.metrics.reused)
            assertEquals(3, cold.metrics.components)
            assertEquals(coldSources.map(SharedReplSource::name).toSet(), cold.affectedPaths.toSet())
            val coldEvaluation = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    cold.generation,
                    cold.affectedPaths,
                    Script::class.java.classLoader
                )
            ).generation

            val independentSources = sources(base = 40, independent = 8)
            val independent = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(independentSources, cold.generation)
            )
            assertEquals(1, independent.metrics.compiled)
            assertEquals(2, independent.metrics.reused)
            assertEquals(listOf("20-independent.eternal.kts"), independent.affectedPaths)
            val independentEvaluation = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    independent.generation,
                    independent.affectedPaths,
                    Script::class.java.classLoader,
                    coldEvaluation
                )
            ).generation
            assertSame(
                coldEvaluation.scripts.getValue("00-provider.eternal.kts").instance,
                independentEvaluation.scripts.getValue("00-provider.eternal.kts").instance
            )
            assertSame(
                coldEvaluation.scripts.getValue("10-consumer.eternal.kts").instance,
                independentEvaluation.scripts.getValue("10-consumer.eternal.kts").instance
            )
            assertNotSame(
                coldEvaluation.scripts.getValue("20-independent.eternal.kts").instance,
                independentEvaluation.scripts.getValue("20-independent.eternal.kts").instance
            )

            val providerSources = sources(base = 41, independent = 8)
            val provider = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(providerSources, independent.generation)
            )
            assertEquals(2, provider.metrics.compiled)
            assertEquals(1, provider.metrics.reused)
            assertEquals(
                setOf("00-provider.eternal.kts", "10-consumer.eternal.kts"),
                provider.affectedPaths.toSet()
            )
            val providerEvaluation = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    provider.generation,
                    provider.affectedPaths,
                    Script::class.java.classLoader,
                    independentEvaluation
                )
            ).generation
            assertNotSame(
                independentEvaluation.scripts.getValue("00-provider.eternal.kts").instance,
                providerEvaluation.scripts.getValue("00-provider.eternal.kts").instance
            )
            assertNotSame(
                independentEvaluation.scripts.getValue("10-consumer.eternal.kts").instance,
                providerEvaluation.scripts.getValue("10-consumer.eternal.kts").instance
            )
            assertSame(
                independentEvaluation.scripts.getValue("20-independent.eternal.kts").instance,
                providerEvaluation.scripts.getValue("20-independent.eternal.kts").instance
            )
            val consumer = providerEvaluation.scripts.getValue("10-consumer.eternal.kts").instance
            assertEquals(43, consumer.javaClass.getMethod("getResult").invoke(consumer))

            coldEvaluation.close()
            cold.generation.close()
            independentEvaluation.close()
            independent.generation.close()
            compiledGeneration = provider.generation
            evaluatedGeneration = providerEvaluation
        } finally {
            evaluatedGeneration?.close()
            compiledGeneration?.close()
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `adding a consumer reuses its provider when both imports stay file local`() {
        val temp = Files.createTempDirectory("eternalscript-file-local-incremental")
        val compiledGenerations = mutableListOf<CompiledComponentGeneration>()
        val evaluatedGenerations = mutableListOf<EvaluatedComponentGeneration>()
        try {
            val compiler = ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader)
            val providerSource = SharedReplSource(
                "00-provider.eternal.kts",
                """
                    import java.time.Duration as Span

                    fun providerSeconds(): Long = Span.ofSeconds(40).seconds
                """.trimIndent()
            )
            val provider = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(listOf(providerSource))
            )
            compiledGenerations += provider.generation
            val providerEvaluation = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    provider.generation,
                    provider.affectedPaths,
                    Script::class.java.classLoader
                )
            ).generation
            evaluatedGenerations += providerEvaluation

            val consumerSource = SharedReplSource(
                "10-consumer.eternal.kts",
                """
                    import java.time.Duration as Span

                    val result: Long = providerSeconds() + Span.ofSeconds(2).seconds
                """.trimIndent()
            )
            val consumer = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(listOf(providerSource, consumerSource), provider.generation)
            )
            compiledGenerations += consumer.generation
            assertEquals(1, consumer.metrics.compiled)
            assertEquals(1, consumer.metrics.reused)
            assertEquals(listOf("10-consumer.eternal.kts"), consumer.affectedPaths)

            val consumerEvaluation = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    consumer.generation,
                    consumer.affectedPaths,
                    Script::class.java.classLoader,
                    providerEvaluation
                )
            ).generation
            evaluatedGenerations += consumerEvaluation
            assertSame(
                providerEvaluation.scripts.getValue("00-provider.eternal.kts").instance,
                consumerEvaluation.scripts.getValue("00-provider.eternal.kts").instance
            )
            val evaluatedConsumer = consumerEvaluation.scripts.getValue("10-consumer.eternal.kts").instance
            assertEquals(42L, evaluatedConsumer.javaClass.getMethod("getResult").invoke(evaluatedConsumer))
        } finally {
            evaluatedGenerations.asReversed().forEach(EvaluatedComponentGeneration::close)
            compiledGenerations.asReversed().forEach(CompiledComponentGeneration::close)
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `forced unchanged target recreates only that independent script`() {
        val temp = Files.createTempDirectory("eternalscript-forced-target")
        val compiledGenerations = mutableListOf<CompiledComponentGeneration>()
        val evaluatedGenerations = mutableListOf<EvaluatedComponentGeneration>()
        try {
            val compiler = ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader)
            val unchangedSources = sources(base = 40, independent = 7)
            val cold = assertIs<ComponentCompilationResult.Success>(compiler.compile(unchangedSources))
            compiledGenerations += cold.generation
            val coldEvaluation = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    cold.generation,
                    cold.affectedPaths,
                    Script::class.java.classLoader
                )
            ).generation
            evaluatedGenerations += coldEvaluation

            val forced = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(
                    unchangedSources,
                    cold.generation,
                    forcedPaths = setOf("20-independent.eternal.kts")
                )
            )
            compiledGenerations += forced.generation
            assertEquals(1, forced.metrics.compiled)
            assertEquals(2, forced.metrics.reused)
            assertEquals(listOf("20-independent.eternal.kts"), forced.affectedPaths)

            val forcedEvaluation = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    forced.generation,
                    forced.affectedPaths,
                    Script::class.java.classLoader,
                    coldEvaluation
                )
            ).generation
            evaluatedGenerations += forcedEvaluation
            assertSame(
                coldEvaluation.scripts.getValue("00-provider.eternal.kts").instance,
                forcedEvaluation.scripts.getValue("00-provider.eternal.kts").instance
            )
            assertSame(
                coldEvaluation.scripts.getValue("10-consumer.eternal.kts").instance,
                forcedEvaluation.scripts.getValue("10-consumer.eternal.kts").instance
            )
            assertNotSame(
                coldEvaluation.scripts.getValue("20-independent.eternal.kts").instance,
                forcedEvaluation.scripts.getValue("20-independent.eternal.kts").instance
            )
        } finally {
            evaluatedGenerations.asReversed().forEach(EvaluatedComponentGeneration::close)
            compiledGenerations.asReversed().forEach(CompiledComponentGeneration::close)
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `recompiles every member of a mutually dependent SCC but keeps independent components`() {
        val temp = Files.createTempDirectory("eternalscript-mutual-components")
        var coldGeneration: CompiledComponentGeneration? = null
        var reloadedGeneration: CompiledComponentGeneration? = null
        try {
            val compiler = ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader)
            val cold = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(mutualSources(leftOffset = 1))
            )
            coldGeneration = cold.generation
            val mutual = cold.generation.graph.componentOf("00-left.eternal.kts")
            assertEquals(
                setOf("00-left.eternal.kts", "10-right.eternal.kts"),
                mutual?.paths?.toSet()
            )

            val reloaded = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(mutualSources(leftOffset = 2), cold.generation)
            )
            reloadedGeneration = reloaded.generation
            assertEquals(2, reloaded.metrics.compiled)
            assertEquals(1, reloaded.metrics.reused)
            assertEquals(
                setOf("00-left.eternal.kts", "10-right.eternal.kts"),
                reloaded.affectedPaths.toSet()
            )
            assertSame(
                cold.generation.components.getValue(
                    cold.generation.graph.componentOf("20-independent.eternal.kts")!!.id
                ).artifact,
                reloaded.generation.components.getValue(
                    reloaded.generation.graph.componentOf("20-independent.eternal.kts")!!.id
                ).artifact
            )
        } finally {
            reloadedGeneration?.close()
            coldGeneration?.close()
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `local import changes invalidate only their component while environment changes invalidate all`() {
        val temp = Files.createTempDirectory("eternalscript-import-components")
        val generations = mutableListOf<CompiledComponentGeneration>()
        try {
            val compiler = ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader)
            val cold = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(importSources("java.math.BigInteger"))
            )
            generations += cold.generation

            val importChanged = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(importSources("java.math.BigDecimal"), cold.generation)
            )
            generations += importChanged.generation
            assertEquals(1, importChanged.metrics.compiled)
            assertEquals(1, importChanged.metrics.reused)
            assertEquals(listOf("00-import.eternal.kts"), importChanged.affectedPaths)

            val environmentChanged = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(importSources("java.math.BigDecimal"), importChanged.generation, forceAll = true)
            )
            generations += environmentChanged.generation
            assertEquals(2, environmentChanged.metrics.compiled)
            assertEquals(0, environmentChanged.metrics.reused)
            assertEquals(importSources("java.math.BigDecimal").map(SharedReplSource::name), environmentChanged.affectedPaths)
        } finally {
            generations.asReversed().forEach(CompiledComponentGeneration::close)
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `only failures before the first top level evaluation are cache retryable`() {
        val temp = Files.createTempDirectory("eternalscript-evaluation-retry")
        val generations = mutableListOf<CompiledComponentGeneration>()
        try {
            val compiler = ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader)
            val missingArtifact = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(listOf(SharedReplSource("00-safe.eternal.kts", "val answer: Int = 42")))
            ).generation
            generations += missingArtifact
            Files.delete(missingArtifact.components.values.single().artifact.jar)
            val beforeTopLevel = assertIs<ComponentEvaluationResult.Failure>(
                ComponentGenerationEvaluator.evaluate(
                    missingArtifact,
                    missingArtifact.graph.initializationOrder,
                    Script::class.java.classLoader
                )
            )
            assertTrue(beforeTopLevel.cacheRetryable)

            val throwing = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(listOf(SharedReplSource("00-throw.eternal.kts", "error(\"top-level\")")))
            ).generation
            generations += throwing
            val duringTopLevel = assertIs<ComponentEvaluationResult.Failure>(
                ComponentGenerationEvaluator.evaluate(
                    throwing,
                    throwing.graph.initializationOrder,
                    Script::class.java.classLoader
                )
            )
            assertFalse(duringTopLevel.cacheRetryable)
        } finally {
            generations.asReversed().forEach(CompiledComponentGeneration::close)
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `evaluation failure disposes the failing script and earlier evaluated scripts`() {
        val temp = Files.createTempDirectory("eternalscript-evaluation-disposal")
        val property = "eternalscript.k2.evaluation-disposal"
        var generation: CompiledComponentGeneration? = null
        try {
            System.setProperty(property, "")
            val compiled = assertIs<ComponentCompilationResult.Success>(
                ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader).compile(
                    listOf(
                        SharedReplSource(
                            "00-owner.eternal.kts",
                            """
                                val ownerMarker: String = "owner"
                                onDispose {
                                    System.setProperty(
                                        "$property",
                                        System.getProperty("$property").orEmpty() + ownerMarker
                                    )
                                }
                            """.trimIndent()
                        ),
                        SharedReplSource(
                            "10-failing.eternal.kts",
                            """
                                val ownerDependency: String = ownerMarker
                                onDispose {
                                    System.setProperty(
                                        "$property",
                                        System.getProperty("$property").orEmpty() + "failing,"
                                    )
                                }
                                error("top-level failure")
                            """.trimIndent()
                        )
                    )
                )
            )
            generation = compiled.generation

            assertIs<ComponentEvaluationResult.Failure>(
                ComponentGenerationEvaluator.evaluate(
                    compiled.generation,
                    compiled.affectedPaths,
                    Script::class.java.classLoader
                )
            )

            assertEquals("failing,owner", System.getProperty(property))
        } finally {
            System.clearProperty(property)
            generation?.close()
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `post evaluation reflection failure disposes the evaluated script`() {
        val temp = Files.createTempDirectory("eternalscript-post-evaluation-disposal")
        val property = "eternalscript.k2.post-evaluation-disposal"
        var generation: CompiledComponentGeneration? = null
        try {
            System.clearProperty(property)
            val compiled = assertIs<ComponentCompilationResult.Success>(
                ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader).compile(
                    listOf(
                        SharedReplSource(
                            "00-owner.eternal.kts",
                            """
                                onDispose { System.setProperty("$property", "disposed") }
                                42
                            """.trimIndent()
                        )
                    )
                )
            )
            generation = CompiledComponentGeneration(
                compiled.generation.graph,
                compiled.generation.sources,
                compiled.generation.components.mapValues { (_, component) ->
                    component.copy(
                        scripts = component.scripts.map { script ->
                            script.copy(resultFieldName = "missing-result-field")
                        }
                    )
                }
            )

            assertIs<ComponentEvaluationResult.Failure>(
                ComponentGenerationEvaluator.evaluate(
                    requireNotNull(generation),
                    compiled.affectedPaths,
                    Script::class.java.classLoader
                )
            )

            assertEquals("disposed", System.getProperty(property))
        } finally {
            System.clearProperty(property)
            generation?.close()
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `keeps a deleted script in the affected paths so runtime resources are unloaded`() {
        val temp = Files.createTempDirectory("eternalscript-deleted-component")
        var coldGeneration: CompiledComponentGeneration? = null
        var deletedGeneration: CompiledComponentGeneration? = null
        try {
            val compiler = ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader)
            val cold = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(sources(base = 40, independent = 7))
            )
            coldGeneration = cold.generation
            val withoutIndependent = sources(base = 40, independent = 7)
                .filterNot { source -> source.name == "20-independent.eternal.kts" }
            val deleted = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(withoutIndependent, cold.generation)
            )
            deletedGeneration = deleted.generation

            assertEquals(listOf("20-independent.eternal.kts"), deleted.affectedPaths)
            assertEquals(0, deleted.metrics.compiled)
            assertEquals(2, deleted.metrics.reused)
        } finally {
            deletedGeneration?.close()
            coldGeneration?.close()
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps explicit import priority local across separate components`() {
        val temp = Files.createTempDirectory("eternalscript-component-import-priority")
        var compiledGeneration: CompiledComponentGeneration? = null
        var evaluatedGeneration: EvaluatedComponentGeneration? = null
        try {
            val conflictingConfiguration = ScriptCompilationConfiguration(configuration) {
                defaultImports.append("java.sql.Date")
            }
            val result = ScriptComponentCompiler(
                conflictingConfiguration,
                temp,
                Script::class.java.classLoader
            ).compile(
                listOf(
                    SharedReplSource(
                        "00-util.eternal.kts",
                        "import java.util.Date\nval utilDate: Date = Date(0)"
                    ),
                    SharedReplSource("10-sql.eternal.kts", "val sqlDate: Date = Date(0)")
                )
            )
            val compiled = assertIs<ComponentCompilationResult.Success>(
                result,
                (result as? ComponentCompilationResult.Failure)?.diagnostic?.let { "${it.source}: ${it.message}" }
            )
            compiledGeneration = compiled.generation
            assertEquals(2, compiled.generation.graph.components.size)
            val evaluated = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    compiled.generation,
                    compiled.affectedPaths,
                    Script::class.java.classLoader
                )
            ).generation
            evaluatedGeneration = evaluated
            val util = evaluated.scripts.getValue("00-util.eternal.kts").instance
            val sql = evaluated.scripts.getValue("10-sql.eternal.kts").instance
            assertEquals(java.util.Date(0), util.javaClass.getMethod("getUtilDate").invoke(util))
            assertEquals(java.sql.Date(0), sql.javaClass.getMethod("getSqlDate").invoke(sql))
        } finally {
            evaluatedGeneration?.close()
            compiledGeneration?.close()
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `typealias changes recompile every dependent component`() {
        val temp = Files.createTempDirectory("eternalscript-typealias-components")
        var coldGeneration: CompiledComponentGeneration? = null
        var reloadedGeneration: CompiledComponentGeneration? = null
        var evaluatedGeneration: EvaluatedComponentGeneration? = null
        try {
            val compiler = ScriptComponentCompiler(configuration, temp, Script::class.java.classLoader)
            val cold = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(typeAliasSources("String", "\"value\""))
            )
            coldGeneration = cold.generation
            assertEquals(
                setOf("00-alias.eternal.kts"),
                cold.generation.graph.dependencies.getValue("10-consumer.eternal.kts")
            )

            val reloaded = assertIs<ComponentCompilationResult.Success>(
                compiler.compile(typeAliasSources("CharSequence", "\"value\""), cold.generation)
            )
            reloadedGeneration = reloaded.generation
            assertEquals(
                setOf("00-alias.eternal.kts", "10-consumer.eternal.kts"),
                reloaded.affectedPaths.toSet()
            )
            assertEquals(2, reloaded.metrics.compiled)

            val evaluated = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    reloaded.generation,
                    reloaded.affectedPaths,
                    Script::class.java.classLoader
                )
            ).generation
            evaluatedGeneration = evaluated
            val consumer = evaluated.scripts.getValue("10-consumer.eternal.kts").instance
            assertEquals(CharSequence::class.java, consumer.javaClass.getMethod("getAliasValue").returnType)
        } finally {
            evaluatedGeneration?.close()
            reloadedGeneration?.close()
            coldGeneration?.close()
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `runtime dependency loader exposes jars that are present only on the script classpath`() {
        val temp = Files.createTempDirectory("eternalscript-runtime-library")
        var compiledGeneration: CompiledComponentGeneration? = null
        var evaluatedGeneration: EvaluatedComponentGeneration? = null
        val libraryJar = buildJavaLibrary(temp)
        val runtimeLoader = RuntimeDependencyClassLoader(
            arrayOf(libraryJar.toUri().toURL()),
            Script::class.java.classLoader,
            emptyList()
        )
        try {
            val withLibrary = configuration.withUpdatedClasspath(listOf(libraryJar.toFile()))
            val compiled = assertIs<ComponentCompilationResult.Success>(
                ScriptComponentCompiler(withLibrary, temp.resolve("components"), Script::class.java.classLoader)
                    .compile(
                        listOf(
                            SharedReplSource(
                                "00-library.eternal.kts",
                                "import audit.lib.ExternalValue\nval libraryValue: String = ExternalValue.value()"
                            )
                        )
                    )
            )
            compiledGeneration = compiled.generation
            val evaluated = assertIs<ComponentEvaluationResult.Success>(
                ComponentGenerationEvaluator.evaluate(
                    compiled.generation,
                    compiled.affectedPaths,
                    runtimeLoader
                )
            ).generation
            evaluatedGeneration = evaluated
            val script = evaluated.scripts.getValue("00-library.eternal.kts").instance
            assertEquals("from-library", script.javaClass.getMethod("getLibraryValue").invoke(script))
        } finally {
            evaluatedGeneration?.close()
            compiledGeneration?.close()
            runtimeLoader.close()
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `compilation remains functional when the persistent cache path cannot hold live artifacts`() {
        val temp = Files.createTempDirectory("eternalscript-cache-optional")
        val blockedArtifactRoot = temp.resolve("not-a-directory")
        Files.writeString(blockedArtifactRoot, "blocked")
        var generation: CompiledComponentGeneration? = null
        try {
            val result = assertIs<ComponentCompilationResult.Success>(
                ScriptComponentCompiler(configuration, blockedArtifactRoot, Script::class.java.classLoader)
                    .compile(listOf(SharedReplSource("00-safe.eternal.kts", "val answer: Int = 42")))
            )
            generation = result.generation
            assertTrue(Files.isRegularFile(result.generation.components.values.single().artifact.jar))
            assertFalse(result.generation.components.values.single().artifact.jar.startsWith(blockedArtifactRoot))
        } finally {
            generation?.close()
            temp.toFile().deleteRecursively()
        }
    }

    private fun sources(base: Int, independent: Int): List<SharedReplSource> = listOf(
        SharedReplSource(
            "00-provider.eternal.kts",
            """
                val base: Int = $base
                fun plusBase(value: Int): Int = base + value
            """.trimIndent()
        ),
        SharedReplSource(
            "10-consumer.eternal.kts",
            "val result: Int = plusBase(2)"
        ),
        SharedReplSource(
            "20-independent.eternal.kts",
            "val independent: Int = $independent"
        )
    )

    private fun mutualSources(leftOffset: Int): List<SharedReplSource> = listOf(
        SharedReplSource(
            "00-left.eternal.kts",
            """
                class Left(val right: Right?)
                fun leftValue(): Int = rightValue() + $leftOffset
            """.trimIndent()
        ),
        SharedReplSource(
            "10-right.eternal.kts",
            """
                class Right(val left: Left?)
                fun rightValue(): Int = 40
            """.trimIndent()
        ),
        SharedReplSource("20-independent.eternal.kts", "val independent: Int = 7")
    )

    private fun importSources(importName: String): List<SharedReplSource> = listOf(
        SharedReplSource(
            "00-import.eternal.kts",
            """
                import $importName

                val first: Int = 1
            """.trimIndent()
        ),
        SharedReplSource("10-independent.eternal.kts", "val second: Int = 2")
    )

    private fun typeAliasSources(type: String, value: String): List<SharedReplSource> = listOf(
        SharedReplSource("00-alias.eternal.kts", "typealias SharedAlias = $type"),
        SharedReplSource("10-consumer.eternal.kts", "val aliasValue: SharedAlias = $value")
    )

    private fun buildJavaLibrary(root: java.nio.file.Path): java.nio.file.Path {
        val source = root.resolve("java/audit/lib/ExternalValue.java")
        val classes = root.resolve("java-classes")
        Files.createDirectories(source.parent)
        Files.createDirectories(classes)
        Files.writeString(
            source,
            "package audit.lib; public final class ExternalValue { " +
                "public static String value() { return \"from-library\"; } }"
        )
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
        val jar = root.resolve("external-library.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classes).use { paths ->
                paths.filter(Files::isRegularFile).forEach { file ->
                    val name = classes.relativize(file).toString().replace('\\', '/')
                    output.putNextEntry(JarEntry(name))
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
        }
        return jar
    }
}
