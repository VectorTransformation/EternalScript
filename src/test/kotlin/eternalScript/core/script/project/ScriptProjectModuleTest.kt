package eternalScript.core.script.project

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files
import kotlin.script.experimental.jvm.util.classpathFromClassloader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScriptProjectModuleTest {
    @Test
    fun `ordinary module preserves sources and compiles cross package references`() {
        val providerText = """
            @file:Suppress("unused")

            package demo.state

            internal var shared = 7
            internal fun fromState() = shared
            private fun hidden() = shared
        """.trimIndent()
        val consumerText = """
            package demo.consumer

            import demo.state.fromState

            fun consume(): Int = fromState()
        """.trimIndent()
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile("state/provider.kt", providerText),
                ScriptProjectFile("consumer.kt", consumerText)
            )
        )

        val module = project.module
        val result = compile(module)

        assertEquals(ExitCode.OK, result.exitCode, result.messages.joinToString("\n"))
        assertEquals(providerText, module.files.single { it.name == "state/provider.kt" }.text)
        assertEquals(consumerText, module.files.single { it.name == "consumer.kt" }.text)
    }

    @Test
    fun `ordinary module keeps private declarations file local`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "provider.kt",
                    """
                    package demo

                    private fun hidden() = 1
                    internal fun visible() = hidden()
                    """.trimIndent()
                ),
                ScriptProjectFile(
                    "consumer.kt",
                    """
                    package demo

                    fun consume() = hidden()
                    """.trimIndent()
                )
            )
        )

        val result = compile(project.module)

        assertTrue(
            result.messages.any { message ->
                message.severity.isError && message.text.contains("hidden")
            }
        )
    }

    @Test
    fun `entry aliases compile and bootstrap calls entries by normalized path`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "zeta.kt",
                    """
                    package demo.zeta

                    import eternalScript.api.script.EternalScriptEntry
                    import eternalScript.core.script.Script

                    @EternalScriptEntry
                    fun Script.loadZeta() = Unit
                    """.trimIndent()
                ),
                ScriptProjectFile(
                    "Alpha.kt",
                    """
                    package demo.alpha

                    import eternalScript.api.script.EternalScriptEntry as Entry
                    import eternalScript.core.script.Script as RuntimeScript

                    @Entry
                    internal fun RuntimeScript.loadAlpha(): Unit = Unit
                    """.trimIndent()
                )
            )
        )

        val module = project.module
        val result = compile(module)
        val entries = module.files.mapNotNull(ScriptProjectModuleFile::entryPoint)
            .associateBy { entry -> entry.sourcePosition.sourceName }
        val runtime = module.files.single { file ->
            file.name == "-eternalscript-generated/EternalScriptProjectRuntime.kt"
        }
        val alpha = assertNotNull(entries["Alpha.kt"])
        val zeta = assertNotNull(entries["zeta.kt"])

        assertEquals(ExitCode.OK, result.exitCode, result.messages.joinToString("\n"))
        assertTrue(runtime.text.contains("import demo.alpha.loadAlpha as ${alpha.importAlias}"))
        assertTrue(runtime.text.contains("import demo.zeta.loadZeta as ${zeta.importAlias}"))
        assertTrue(
            runtime.text.indexOf("script.${alpha.importAlias}()") <
                runtime.text.indexOf("script.${zeta.importAlias}()")
        )
    }

    @Test
    fun `default package entry compiles through a bootstrap import alias`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "default.kt",
                    """
                    import eternalScript.api.script.EternalScriptEntry
                    import eternalScript.core.script.Script

                    @EternalScriptEntry
                    internal fun Script.loadDefault(): Unit = Unit
                    """.trimIndent()
                )
            )
        )

        val result = compile(project.module)

        assertEquals(ExitCode.OK, result.exitCode, result.messages.joinToString("\n"))
    }

    @Test
    fun `bootstrap entry call maps to the entry declaration`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "nested/entry.kt",
                    """
                    package demo

                    import eternalScript.api.script.EternalScriptEntry
                    import eternalScript.core.script.Script

                    @EternalScriptEntry
                    internal fun Script.install(): Unit = Unit
                    """.trimIndent()
                )
            )
        )
        val module = project.module
        val entry = assertNotNull(
            module.files.single { file -> file.name == "nested/entry.kt" }.entryPoint
        )
        val runtime = module.files.single { file ->
            file.name == "-eternalscript-generated/EternalScriptProjectRuntime.kt"
        }
        val callOffset = runtime.text.lastIndexOf(entry.importAlias)
        val (line, column) = runtime.text.position(callOffset)

        assertEquals(
            entry.sourcePosition,
            module.position(runtime.name, line, column)
        )
    }

    @Test
    fun `ordinary compiler diagnostics retain original path line and column`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "nested/broken.kt",
                    """
                    package demo

                    fun broken() {
                        missingFromProject()
                    }
                    """.trimIndent()
                )
            )
        )

        val result = compile(project.module)
        val error = assertNotNull(
            result.messages.firstOrNull { message ->
                message.severity.isError &&
                    message.text.contains("missingFromProject") &&
                    message.location != null
            }
        )
        val location = assertNotNull(error.location)

        assertEquals(
            ScriptProjectPosition("nested/broken.kt", 4, 5),
            project.module.position(
                assertNotNull(location.path),
                location.line,
                location.column
            )
        )
    }

    @Test
    fun `user file facade annotations keep ordinary Kotlin meaning`() {
        val source = """
            @file:JvmName("CustomFacade")

            package demo

            fun exposed() = 1
        """.trimIndent()
        val project = ScriptProjectSource.compose(
            listOf(ScriptProjectFile("custom.kt", source))
        )

        val moduleFile = project.module.files.single { file -> file.name == "custom.kt" }
        val result = compile(project.module)

        assertEquals(source, moduleFile.text)
        assertEquals("demo.CustomFacade", moduleFile.facadeClassName)
        assertEquals("demo.CustomFacade", moduleFile.exportClassName)
        assertEquals(ExitCode.OK, result.exitCode, result.messages.joinToString("\n"))
    }

    @Test
    fun `multifile facade annotations preserve source and part class names`() {
        val firstSource = """
            @file:JvmName("SharedFacade")
            @file:JvmMultifileClass

            package demo

            fun first() = 1
        """.trimIndent()
        val secondSource = """
            @file:JvmName("SharedFacade")
            @file:JvmMultifileClass

            package demo

            fun second() = first() + 1
        """.trimIndent()
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile("first.kt", firstSource),
                ScriptProjectFile("second.kt", secondSource)
            )
        )

        val first = project.module.files.single { file -> file.name == "first.kt" }
        val second = project.module.files.single { file -> file.name == "second.kt" }
        val result = compile(project.module)

        assertEquals(firstSource, first.text)
        assertEquals(secondSource, second.text)
        assertEquals("demo.SharedFacade__FirstKt", first.facadeClassName)
        assertEquals("demo.SharedFacade__SecondKt", second.facadeClassName)
        assertEquals("demo.SharedFacade", first.exportClassName)
        assertEquals("demo.SharedFacade", second.exportClassName)
        assertEquals(ExitCode.OK, result.exitCode, result.messages.joinToString("\n"))
    }

    @Test
    fun `rejects multiple entry functions in one source file`() {
        val exception = assertFailsWith<ScriptProjectCompositionException> {
            ScriptProjectSource.compose(
                listOf(
                    ScriptProjectFile(
                        "duplicate.kt",
                        """
                        import eternalScript.api.script.EternalScriptEntry
                        import eternalScript.core.script.Script

                        @EternalScriptEntry
                        fun Script.first() = Unit

                        @EternalScriptEntry
                        fun Script.second() = Unit
                        """.trimIndent()
                    )
                )
            ).module
        }

        assertTrue(exception.message.orEmpty().contains("at most one"))
    }

    @Test
    fun `rejects entry declarations outside the ordinary entry contract`() {
        val invalidSources = listOf(
            "@EternalScriptEntry private fun Script.invalid() = Unit" to "public or internal",
            "@EternalScriptEntry suspend fun Script.invalid() = Unit" to "must not be suspend",
            "@EternalScriptEntry fun <T> Script.invalid() = Unit" to "type parameters",
            "@EternalScriptEntry fun Script.invalid(value: Int) = Unit" to "value parameters",
            "@EternalScriptEntry fun String.invalid() = Unit" to "receiver must be exactly",
            "@EternalScriptEntry fun Script.invalid(): Int = 1" to "return kotlin.Unit",
            "class Nested { @EternalScriptEntry fun Script.invalid() = Unit }" to "top-level"
        )

        invalidSources.forEach { (declaration, expectedMessage) ->
            val exception = assertFailsWith<ScriptProjectCompositionException>(declaration) {
                ScriptProjectSource.compose(
                    listOf(
                        ScriptProjectFile(
                            "invalid.kt",
                            """
                            import eternalScript.api.script.EternalScriptEntry
                            import eternalScript.core.script.Script

                            $declaration
                            """.trimIndent()
                        )
                    )
                ).module
            }
            assertTrue(
                exception.message.orEmpty().contains(expectedMessage),
                exception.message
            )
        }
    }

    @Test
    fun `bootstrap enforces inferred Unit entry return type`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "non-unit.kt",
                    """
                    import eternalScript.api.script.EternalScriptEntry
                    import eternalScript.core.script.Script

                    @EternalScriptEntry
                    fun Script.invalid() = 1
                    """.trimIndent()
                )
            )
        )

        val result = compile(project.module)
        val error = assertNotNull(
            result.messages.firstOrNull { message ->
                message.severity.isError && message.location != null
            }
        )
        val location = assertNotNull(error.location)
        val mapped = project.module.position(
            assertNotNull(location.path),
            location.line,
            location.column
        )

        assertEquals("non-unit.kt", mapped?.sourceName)
        assertEquals(5, mapped?.line)
    }

    @Test
    fun `wildcard imports do not make a local marker an EternalScript entry`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "local-marker.kt",
                    """
                    package project.local

                    import eternalScript.api.script.*
                    import eternalScript.core.script.Script

                    annotation class EternalScriptEntry

                    @EternalScriptEntry
                    fun Script.notAnEntry() = Unit
                    """.trimIndent()
                )
            )
        )

        assertEquals(
            null,
            project.module.files.single { file -> file.name == "local-marker.kt" }.entryPoint
        )
    }

    private fun compile(module: ScriptProjectModule): CompilerResult {
        val root = Files.createTempDirectory("eternal-script-module-test").toFile()
        return try {
            val sources = File(root, "sources").apply(File::mkdirs)
            val classes = File(root, "classes").apply(File::mkdirs)
            val sourceFiles = module.files.map { source ->
                File(sources, source.name).apply {
                    parentFile.mkdirs()
                    writeText(source.text)
                }
            }
            val classpath = classpathFromClassloader(javaClass.classLoader)
                .orEmpty()
                .joinToString(File.pathSeparator, transform = File::getAbsolutePath)
            val collector = CollectingMessageCollector()
            val arguments = K2JVMCompilerArguments().apply {
                destination = classes.absolutePath
                this.classpath = classpath
                jvmTarget = "21"
                moduleName = "eternal-script-module-test"
                noStdlib = true
                noReflect = true
                freeArgs = sourceFiles.map(File::getAbsolutePath)
            }
            val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
            CompilerResult(exitCode, collector.messages.toList())
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun String.position(offset: Int): Pair<Int, Int> {
    require(offset >= 0)
    val prefix = substring(0, offset)
    val line = prefix.count { character -> character == '\n' } + 1
    val column = offset - prefix.lastIndexOf('\n')
    return line to column
}

private data class CompilerResult(
    val exitCode: ExitCode,
    val messages: List<CompilerMessage>
)

private data class CompilerMessage(
    val severity: CompilerMessageSeverity,
    val text: String,
    val location: CompilerMessageSourceLocation?
)

private class CollectingMessageCollector : MessageCollector {
    val messages = mutableListOf<CompilerMessage>()

    override fun clear() {
        messages.clear()
    }

    override fun hasErrors() = messages.any { message -> message.severity.isError }

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?
    ) {
        messages += CompilerMessage(severity, message, location)
    }
}
