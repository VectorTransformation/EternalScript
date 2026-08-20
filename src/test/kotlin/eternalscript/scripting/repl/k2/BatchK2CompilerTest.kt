package eternalscript.scripting.repl.k2

import eternalscript.api.script.Script
import eternalscript.scripting.repl.SCRIPT_JVM_TARGET
import eternalscript.scripting.repl.ScriptCompilerConfig
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.runtime.ReplStateBridge
import java.math.BigInteger
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.isStandalone
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BatchK2CompilerTest {
    private val configuration = ScriptCompilationConfiguration(ScriptCompilerConfig) {
        isStandalone(false)
        defaultImports("java.time.Instant")
        jvm {
            dependenciesFromClassContext(Script::class, wholeClasspath = true)
            updateClasspath(
                listOf(java.io.File(Script::class.java.protectionDomain.codeSource.location.toURI()))
            )
            compilerOptions.append("-jvm-target=$SCRIPT_JVM_TARGET")
        }
    }

    @Test
    fun `compiler adapter ABI matches the embedded Kotlin compiler`() {
        assertEquals(
            KotlinCompilerVersion.VERSION,
            K2_REPL_COMPILER_ABI.substringBefore("-eternalscript")
        )
    }

    @Test
    fun `records importable script classifiers without loading generated classes`() {
        val source = SharedReplSource(
            "classifiers.eternal.kts",
            """
                class SharedClass
                interface SharedInterface
                object SharedObject
                enum class SharedEnum { VALUE }
                typealias SharedAlias = SharedClass
            """.trimIndent()
        )

        val generation = BatchK2Compiler(configuration).use { compiler ->
            assertIs<BatchCompilationResult.Success>(compiler.compile(listOf(source))).generation
        }
        val script = generation.scripts.single()
        assertEquals(
            setOf("SharedClass", "SharedInterface", "SharedObject", "SharedEnum", "SharedAlias"),
            script.classifiers.map(ScriptClassifierDescriptor::name).toSet()
        )
        script.classifiers.forEach { classifier ->
            assertEquals("${script.className}.${classifier.name}", classifier.importPath)
        }
        assertEquals("TYPE_ALIAS", script.classifiers.single { it.name == "SharedAlias" }.kind)
    }

    @Test
    fun `compiles and evaluates bidirectional declarations with file local imports`() {
        val sources = listOf(
            SharedReplSource(
                "00-a.eternal.kts",
                """
                    import java.math.BigInteger

                    fun fromA(depth: Int): String = if (depth == 0) "A" else fromB(depth - 1)
                    class A(val b: B)
                    fun importedTen(): BigInteger = BigInteger.TEN
                    val epoch: Instant = Instant.EPOCH
                    val forwardValue: Int get() = baseValue + 1
                """.trimIndent()
            ),
            SharedReplSource(
                "10-b.eternal.kts",
                """
                    fun fromB(depth: Int): String = if (depth == 0) "B" else fromA(depth - 1)
                    class B(val a: A?)
                    val baseValue = 41
                """.trimIndent()
            ),
            SharedReplSource(
                "20-consumer.eternal.kts",
                """
                    val mutualResult = fromA(2)
                    val classResult = A(B(null)).b.a
                    val importedResult = importedTen()
                    val forwardResult = forwardValue
                    val defaultImportResult = epoch
                """.trimIndent()
            )
        )

        val generation = BatchK2Compiler(configuration).use { compiler ->
            assertIs<BatchCompilationResult.Success>(compiler.compile(sources)).generation
        }
        assertEquals(setOf("10-b.eternal.kts"), generation.graph.dependencies.getValue("00-a.eternal.kts"))
        assertTrue(generation.graph.initializationDependencies.getValue("00-a.eternal.kts").isEmpty())
        assertEquals(
            setOf("00-a.eternal.kts", "10-b.eternal.kts"),
            generation.graph.componentOf("00-a.eternal.kts")?.paths?.toSet()
        )
        val evaluationResult = BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            evaluationResult,
            (evaluationResult as? BatchEvaluationResult.Failure)?.diagnostic?.let { diagnostic ->
                "${diagnostic.source}: ${diagnostic.message}\n${diagnostic.cause?.stackTraceToString()}"
            }
        )
        val consumer = evaluation.scripts.last().instance

        assertEquals("A", consumer.javaClass.getMethod("getMutualResult").invoke(consumer))
        assertEquals(null, consumer.javaClass.getMethod("getClassResult").invoke(consumer))
        assertEquals(BigInteger.TEN, consumer.javaClass.getMethod("getImportedResult").invoke(consumer))
        assertEquals(42, consumer.javaClass.getMethod("getForwardResult").invoke(consumer))
        assertEquals(java.time.Instant.EPOCH, consumer.javaClass.getMethod("getDefaultImportResult").invoke(consumer))
    }

    @Test
    fun `resolves an inferred later property inside an earlier lifecycle lambda`() {
        val property = "eternalscript.k2.inferred-peer"
        val sources = listOf(
            SharedReplSource(
                "00-consumer.eternal.kts",
                """
                    onLoad { System.setProperty("$property", "value=${'$'}a") }
                """.trimIndent()
            ),
            SharedReplSource("10-provider.eternal.kts", "val a = \"a\"")
        )

        val result = BatchK2Compiler(configuration).use { compiler -> compiler.compile(sources) }
        val generation = assertIs<BatchCompilationResult.Success>(
            result,
            (result as? BatchCompilationResult.Failure)?.diagnostic?.let { diagnostic ->
                "${diagnostic.source}:${diagnostic.line}:${diagnostic.column}: ${diagnostic.message}"
            }
        ).generation
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val previousState = ReplStateBridge.publish(evaluation.state)
        try {
            evaluation.scripts
                .single { script -> script.compiled.source.name == "00-consumer.eternal.kts" }
                .script
                .freezeDeclarations()
                .loadCallbacks
                .single()
                .invoke()
            assertEquals("value=a", System.getProperty(property))
        } finally {
            ReplStateBridge.publish(previousState)
            System.clearProperty(property)
        }
    }

    @Test
    fun `local shadowing does not create a peer implicit type cycle`() {
        val sources = listOf(
            SharedReplSource("00-consumer.eternal.kts", "val b = a + 1"),
            SharedReplSource(
                "10-provider.eternal.kts",
                """
                    val a = 41
                    onLoad {
                        val b = 7
                        System.setProperty("eternalscript.k2.local-shadow", b.toString())
                    }
                """.trimIndent()
            )
        )

        val result = BatchK2Compiler(configuration).use { compiler -> compiler.compile(sources) }
        val generation = assertIs<BatchCompilationResult.Success>(
            result,
            (result as? BatchCompilationResult.Failure)?.diagnostic?.let { diagnostic ->
                "${diagnostic.source}:${diagnostic.line}:${diagnostic.column}: ${diagnostic.message}"
            }
        ).generation
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val consumer = evaluation.scripts.single { script ->
            script.compiled.source.name == "00-consumer.eternal.kts"
        }
        assertEquals(42, consumer.instance.javaClass.getMethod("getB").invoke(consumer.instance))
    }

    @Test
    fun `supports aliased and star imports in their declaring file`() {
        val sources = listOf(
            SharedReplSource(
                "00-importer.eternal.kts",
                """
                    import java.util.UUID as Id
                    import java.math.*

                    val aliasValue: Id = Id(0, 0)
                    val starValue: BigInteger = BigInteger.TEN
                """.trimIndent()
            ),
            SharedReplSource("10-independent.eternal.kts", "val independent: Int = 1")
        )
        val result = BatchK2Compiler(configuration).use { compiler -> compiler.compile(sources) }
        val generation = assertIs<BatchCompilationResult.Success>(
            result,
            (result as? BatchCompilationResult.Failure)?.diagnostic?.let { "${it.source}: ${it.message}" }
        ).generation
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val importer = evaluation.scripts.single { script ->
            script.compiled.source.name == "00-importer.eternal.kts"
        }.instance
        assertEquals(java.util.UUID(0, 0), importer.javaClass.getMethod("getAliasValue").invoke(importer))
        assertEquals(BigInteger.TEN, importer.javaClass.getMethod("getStarValue").invoke(importer))
    }

    @Test
    fun `does not leak an explicit import into another file`() {
        val result = BatchK2Compiler(configuration).use { compiler ->
            compiler.compile(
                listOf(
                    SharedReplSource(
                        "00-importer.eternal.kts",
                        "import java.util.UUID as Id\nval localId: Id = Id(0, 0)"
                    ),
                    SharedReplSource("10-consumer.eternal.kts", "val leakedId: Id = Id(0, 0)")
                )
            )
        }
        val failure = assertIs<BatchCompilationResult.Failure>(result)
        assertEquals("10-consumer.eternal.kts", failure.diagnostic.source)
        assertTrue(failure.diagnostic.message.contains("Id"))
    }

    @Test
    fun `compile diagnostics report the logical script path instead of the internal source name`() {
        val result = BatchK2Compiler(configuration).use { compiler ->
            compiler.compile(
                listOf(
                    SharedReplSource(
                        "nested/broken.eternal.kts",
                        "val broken: MissingLogicalPathType = TODO()"
                    )
                )
            )
        }

        val failure = assertIs<BatchCompilationResult.Failure>(result)
        assertEquals("nested/broken.eternal.kts", failure.diagnostic.source)
        assertTrue(failure.diagnostic.message.contains("MissingLogicalPathType"))
    }

    @Test
    fun `local explicit imports coexist and shadow defaults only in their file`() {
        val configurationWithConflict = ScriptCompilationConfiguration(configuration) {
            defaultImports.append("java.sql.Date")
        }
        val result = BatchK2Compiler(configurationWithConflict).use { compiler ->
            compiler.compile(
                listOf(
                    SharedReplSource(
                        "00-util.eternal.kts",
                        "import java.util.Date\nval utilDate: Date = Date(0)"
                    ),
                    SharedReplSource("10-sql.eternal.kts", "val sqlDate: Date = Date(0)")
                )
            )
        }
        val generation = assertIs<BatchCompilationResult.Success>(
            result,
            (result as? BatchCompilationResult.Failure)?.diagnostic?.let { "${it.source}: ${it.message}" }
        ).generation
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val util = evaluation.scripts.single { script ->
            script.compiled.source.name == "00-util.eternal.kts"
        }.instance
        val sql = evaluation.scripts.single { script ->
            script.compiled.source.name == "10-sql.eternal.kts"
        }.instance
        assertEquals(java.util.Date(0), util.javaClass.getMethod("getUtilDate").invoke(util))
        assertEquals(java.sql.Date(0), sql.javaClass.getMethod("getSqlDate").invoke(sql))
    }

    @Test
    fun `evaluates a later provider before an earlier eager consumer`() {
        val generation = BatchK2Compiler(configuration).use { compiler ->
            val result = compiler.compile(
                    listOf(
                        SharedReplSource("00-consumer.eternal.kts", "val answer: Int = provider + 1"),
                        SharedReplSource("10-provider.eternal.kts", "val provider: Int = 41")
                    )
                )
            assertIs<BatchCompilationResult.Success>(
                result,
                (result as? BatchCompilationResult.Failure)?.diagnostic?.let { "${it.source}: ${it.message}" }
            ).generation
        }

        assertEquals(
            listOf("10-provider.eternal.kts", "00-consumer.eternal.kts"),
            generation.graph.initializationOrder
        )
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val consumer = evaluation.scripts.single { script -> script.compiled.source.name == "00-consumer.eternal.kts" }
        assertEquals(42, consumer.instance.javaClass.getMethod("getAnswer").invoke(consumer.instance))
    }

    @Test
    fun `follows an eagerly invoked function body when ordering value providers`() {
        val generation = BatchK2Compiler(configuration).use { compiler ->
            val result = compiler.compile(
                listOf(
                    SharedReplSource(
                        "00-consumer.eternal.kts",
                        "fun compute(): Int = provider + 1\nval answer: Int = compute()"
                    ),
                    SharedReplSource("10-provider.eternal.kts", "val provider: Int = 41")
                )
            )
            assertIs<BatchCompilationResult.Success>(
                result,
                (result as? BatchCompilationResult.Failure)?.diagnostic?.let { "${it.source}: ${it.message}" }
            ).generation
        }

        assertTrue(
            generation.graph.initializationOrder.indexOf("10-provider.eternal.kts") <
                generation.graph.initializationOrder.indexOf("00-consumer.eternal.kts")
        )
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val consumer = evaluation.scripts.single { script ->
            script.compiled.source.name == "00-consumer.eternal.kts"
        }
        assertEquals(42, consumer.instance.javaClass.getMethod("getAnswer").invoke(consumer.instance))
    }

    @Test
    fun `rejects an eager initialization cycle before evaluation`() {
        val result = BatchK2Compiler(configuration).use { compiler ->
            compiler.compile(
                listOf(
                    SharedReplSource("00-a.eternal.kts", "val a: Int = b + 1"),
                    SharedReplSource("10-b.eternal.kts", "val b: Int = a + 1")
                )
            )
        }

        val failure = assertIs<BatchCompilationResult.Failure>(
            result,
            (result as? BatchCompilationResult.Success)?.generation?.graph?.let { graph ->
                "dependencies=${graph.dependencies}, initialization=${graph.initializationDependencies}"
            }
        )
        assertTrue(failure.diagnostic.message.contains("initialization cycle", ignoreCase = true))
    }

    @Test
    fun `orders a provider used by an omitted default argument before the eager caller`() {
        val generation = compileSuccessful(
            listOf(
                SharedReplSource("00-consumer.eternal.kts", "val defaultResult: Int = useDefault()"),
                SharedReplSource(
                    "10-function.eternal.kts",
                    "fun useDefault(value: Int = laterDefaultValue): Int = value"
                ),
                SharedReplSource("20-provider.eternal.kts", "val laterDefaultValue: Int = 42")
            )
        )

        assertBefore(generation, "20-provider.eternal.kts", "00-consumer.eternal.kts")
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val consumer = evaluation.scripts.single { it.compiled.source.name == "00-consumer.eternal.kts" }.instance
        assertEquals(42, consumer.javaClass.getMethod("getDefaultResult").invoke(consumer))
    }

    @Test
    fun `orders a provider read by an eager calls in place lambda before the consumer`() {
        val generation = compileSuccessful(
            listOf(
                SharedReplSource(
                    "00-consumer.eternal.kts",
                    "val inlineResult: Int = run { laterInlineValue }"
                ),
                SharedReplSource("10-provider.eternal.kts", "val laterInlineValue: Int = 42")
            )
        )

        assertBefore(generation, "10-provider.eternal.kts", "00-consumer.eternal.kts")
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val consumer = evaluation.scripts.single { it.compiled.source.name == "00-consumer.eternal.kts" }.instance
        assertEquals(42, consumer.javaClass.getMethod("getInlineResult").invoke(consumer))
    }

    @Test
    fun `orders a provider read by a constructed class initializer before the consumer`() {
        val generation = compileSuccessful(
            listOf(
                SharedReplSource("00-consumer.eternal.kts", "val holderResult: Int = Holder().value"),
                SharedReplSource("10-class.eternal.kts", "class Holder { val value: Int = laterClassValue }"),
                SharedReplSource("20-provider.eternal.kts", "val laterClassValue: Int = 42")
            )
        )

        assertBefore(generation, "20-provider.eternal.kts", "00-consumer.eternal.kts")
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val consumer = evaluation.scripts.single { it.compiled.source.name == "00-consumer.eternal.kts" }.instance
        assertEquals(42, consumer.javaClass.getMethod("getHolderResult").invoke(consumer))
    }

    @Test
    fun `rejects duplicate shared properties and classifiers even when unused`() {
        val result = BatchK2Compiler(configuration).use { compiler ->
            compiler.compile(
                listOf(
                    SharedReplSource(
                        "00-first.eternal.kts",
                        "val sharedName: Int = 1\nclass SharedType"
                    ),
                    SharedReplSource(
                        "10-second.eternal.kts",
                        "val sharedName: Int = 2\nclass SharedType"
                    )
                )
            )
        }

        val failure = assertIs<BatchCompilationResult.Failure>(result)
        assertTrue(failure.diagnostic.message.contains("Duplicate shared declaration"))
        assertTrue(failure.diagnostic.message.contains("sharedName") || failure.diagnostic.message.contains("SharedType"))
    }

    @Test
    fun `compiles a consumer against a previously compiled provider component`() {
        val providerSource = SharedReplSource(
            "10-provider.eternal.kts",
            """
                import java.math.BigInteger

                val providerValue: Int = 41
                fun providerFunction(value: Int): Int = providerValue + value
                class ProviderType(val value: BigInteger)
            """.trimIndent()
        )
        val provider = BatchK2Compiler(configuration).use { compiler ->
            val result = compiler.compile(listOf(providerSource))
            assertIs<BatchCompilationResult.Success>(
                result,
                (result as? BatchCompilationResult.Failure)?.diagnostic?.let { "${it.source}: ${it.message}" }
            ).generation
        }
        val providerEvaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(provider, Script::class.java.classLoader)
        )
        val temp = java.nio.file.Path.of("build", "tmp", "eternalscript-component-test").also(Files::createDirectories)
        try {
            val providerJar = temp.resolve("provider.jar")
            JarOutputStream(Files.newOutputStream(providerJar)).use { output ->
                provider.outputFiles.toSortedMap().forEach { (name, bytes) ->
                    output.putNextEntry(JarEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            val providerScript = provider.scripts.single()
            val consumerConfiguration = ScriptCompilationConfiguration(configuration) {
                defaultImports.append("java.math.BigInteger")
                jvm {
                    dependenciesFromClassContext(Script::class, wholeClasspath = true)
                    updateClasspath(
                        listOf(
                            java.io.File(Script::class.java.protectionDomain.codeSource.location.toURI()),
                            providerJar.toFile()
                        )
                    )
                }
            }
            val consumerSource = SharedReplSource(
                "00-consumer.eternal.kts",
                """
                    val componentAnswer: Int = providerFunction(1)
                    val componentType = ProviderType(BigInteger.TEN)
                """.trimIndent()
            )
            val consumerResult = BatchK2Compiler(
                consumerConfiguration,
                batchScriptingHostConfiguration(
                    listOf(
                        ProviderSnippetDescriptor(
                            providerSource.name,
                            providerScript.className,
                            providerScript.stateKey
                        )
                    )
                )
            ).use { compiler -> compiler.compile(listOf(consumerSource)) }
            val consumer = assertIs<BatchCompilationResult.Success>(
                consumerResult,
                (consumerResult as? BatchCompilationResult.Failure)?.diagnostic?.let { diagnostic ->
                    "${diagnostic.source}: ${diagnostic.message}\n${diagnostic.cause?.stackTraceToString()}"
                }
            ).generation
            val consumerEvaluation = assertIs<BatchEvaluationResult.Success>(
                BatchK2Evaluator.evaluate(
                    consumer,
                    providerEvaluation.classLoader,
                    providerEvaluation.state.copy()
                )
            )
            val instance = consumerEvaluation.scripts.single().instance
            assertEquals(42, instance.javaClass.getMethod("getComponentAnswer").invoke(instance))
            val providerType = instance.javaClass.getMethod("getComponentType").invoke(instance)
            assertEquals(BigInteger.TEN, providerType.javaClass.getMethod("getValue").invoke(providerType))
        } finally {
            temp.toFile().deleteRecursively()
            ReplStateBridge.stage(ReplStateBridge.StateTable()) { ReplStateBridge.clear() }
        }
    }

    @Test
    fun `evaluates lifecycle DSL through the injected Script receiver`() {
        val result = BatchK2Compiler(configuration).use { compiler ->
            compiler.compile(
                listOf(
                    SharedReplSource(
                        "00-lifecycle.eternal.kts",
                        """
                            onLoad { System.setProperty("eternalscript.k2.lifecycle", "loaded") }
                            onUnload { System.clearProperty("eternalscript.k2.lifecycle") }
                        """.trimIndent()
                    )
                )
            )
        }
        val generation = assertIs<BatchCompilationResult.Success>(
            result,
            (result as? BatchCompilationResult.Failure)?.diagnostic?.message
        ).generation
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val declarations = evaluation.scripts.single().script.freezeDeclarations()
        assertEquals(1, declarations.loadCallbacks.size)
        assertEquals(1, declarations.unloadCallbacks.size)
        declarations.loadCallbacks.single().invoke()
        assertEquals("loaded", System.getProperty("eternalscript.k2.lifecycle"))
        declarations.unloadCallbacks.single().invoke()
        assertEquals(null, System.getProperty("eternalscript.k2.lifecycle"))
    }

    @Test
    fun `uses the full logical path for stable unique snippet classes`() {
        val sources = listOf(
            SharedReplSource("a/shared.eternal.kts", "val fromA: Int = 20"),
            SharedReplSource("b/shared.eternal.kts", "val fromB: Int = fromA + 22")
        )
        val generation = BatchK2Compiler(configuration).use { compiler ->
            assertIs<BatchCompilationResult.Success>(compiler.compile(sources)).generation
        }
        assertEquals(2, generation.scripts.map(BatchCompiledScript::className).toSet().size)
        val evaluation = assertIs<BatchEvaluationResult.Success>(
            BatchK2Evaluator.evaluate(generation, Script::class.java.classLoader)
        )
        val consumer = evaluation.scripts.single { script -> script.compiled.source.name == "b/shared.eternal.kts" }
        assertEquals(42, consumer.instance.javaClass.getMethod("getFromB").invoke(consumer.instance))
    }

    private fun compileSuccessful(sources: List<SharedReplSource>): BatchCompiledGeneration {
        val result = BatchK2Compiler(configuration).use { compiler -> compiler.compile(sources) }
        return assertIs<BatchCompilationResult.Success>(
            result,
            (result as? BatchCompilationResult.Failure)?.diagnostic?.let { diagnostic ->
                "${diagnostic.source}: ${diagnostic.message}\n${diagnostic.cause?.stackTraceToString()}"
            }
        ).generation
    }

    private fun assertBefore(generation: BatchCompiledGeneration, provider: String, consumer: String) {
        assertTrue(
            generation.graph.initializationOrder.indexOf(provider) <
                generation.graph.initializationOrder.indexOf(consumer),
            "Expected $provider before $consumer, got ${generation.graph.initializationOrder}"
        )
    }
}
