package eternalScript.core.script.project

import org.jetbrains.kotlin.buildtools.api.CompilationResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.script.experimental.jvm.util.classpathFromClassloader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
class KotlinIncrementalProjectCompilerTest {
    @Test
    fun `nested source paths with duplicate basenames survive rename and delete`() {
        withCompiler { compiler ->
            val firstModule = nestedModule(
                alphaPath = "nested/alpha/shared.kt",
                alphaValue = "v1",
                includeBeta = true
            )
            val first = compiler.compile(firstModule)

            assertSuccessful(first)
            assertEquals(
                firstModule.files.map { file -> file.name }.sorted(),
                compiler.generatedSourcesDirectory.fileTimes().keys.sorted()
            )
            val stableSource =
                compiler.generatedSourcesDirectory.resolve("nested/stable/shared.kt")
            val stableTimestamp = FileTime.fromMillis(1_234_567_890_000L)
            Files.setLastModifiedTime(stableSource, stableTimestamp)
            val firstClasses = compiler.classesDirectory.fileHashes()

            val secondModule = nestedModule(
                alphaPath = "renamed/alpha/shared.kt",
                alphaValue = "v2",
                includeBeta = false
            )
            val second = compiler.compile(secondModule)

            assertSuccessful(second)
            assertEquals(
                secondModule.files.map { file -> file.name }.sorted(),
                compiler.generatedSourcesDirectory.fileTimes().keys.sorted()
            )
            assertFalse(
                Files.exists(
                    compiler.generatedSourcesDirectory.resolve("nested/alpha/shared.kt")
                )
            )
            assertFalse(
                Files.exists(
                    compiler.generatedSourcesDirectory.resolve("nested/beta/shared.kt")
                )
            )
            assertFalse(
                Files.exists(compiler.generatedSourcesDirectory.resolve("nested/alpha"))
            )
            assertFalse(
                Files.exists(compiler.generatedSourcesDirectory.resolve("nested/beta"))
            )
            assertTrue(
                Files.isRegularFile(
                    compiler.generatedSourcesDirectory.resolve("renamed/alpha/shared.kt")
                )
            )
            assertEquals(stableTimestamp, Files.getLastModifiedTime(stableSource))

            val secondClasses = compiler.classesDirectory.fileHashes()
            assertNotEquals(
                firstClasses.getValue("probe/alpha/SharedKt.class"),
                secondClasses.getValue("probe/alpha/SharedKt.class")
            )
            assertEquals(
                firstClasses.getValue("probe/stable/SharedKt.class"),
                secondClasses.getValue("probe/stable/SharedKt.class")
            )
            assertFalse("probe/beta/SharedKt.class" in secondClasses)

            val stateFile =
                compiler.generatedSourcesDirectory.parent.resolve("compiled-sources.tsv")
            val stateNames = Files.readAllLines(stateFile, Charsets.UTF_8)
                .drop(1)
                .map { line -> line.substringBefore('\t') }
            assertEquals(
                secondModule.files.map { file -> file.name }.sorted(),
                stateNames.sorted()
            )

            JarFile(assertNotNull(second.generationJar).toFile(), false).use { jar ->
                assertNotNull(jar.getEntry("probe/alpha/SharedKt.class"))
                assertNotNull(jar.getEntry("probe/stable/SharedKt.class"))
                assertNull(jar.getEntry("probe/beta/SharedKt.class"))
            }
        }
    }

    @Test
    fun `generated source paths reject absolute and traversal forms`() {
        withCompiler { compiler ->
            val sourceRoot = compiler.generatedSourcesDirectory
            assertEquals(
                sourceRoot.resolve("nested/source.kt"),
                compiler.generatedSourcePath("nested/source.kt")
            )
            listOf(
                "../outside.kt",
                "nested/../outside.kt",
                sourceRoot.parent.resolve("outside.kt").toString()
            ).forEach { unsafe ->
                assertFailsWith<IllegalArgumentException>(unsafe) {
                    compiler.generatedSourcePath(unsafe)
                }
            }
        }
    }

    @Test
    fun `incremental generations are cached deterministic and transactional`() {
        withCompiler { compiler ->
            val firstModule = module(message = "v1")
            val first = compiler.compile(firstModule)

            assertSuccessful(first)
            assertFalse(first.cacheHit)
            val firstJar = assertNotNull(first.generationJar)
            val firstJarBytes = Files.readAllBytes(firstJar)
            val firstClasses = compiler.classesDirectory.fileHashes()
            val firstSources = compiler.generatedSourcesDirectory.fileTimes()
            val firstSnapshots = compiler.classpathSnapshotsDirectory.fileTimes()
            assertTrue(firstSnapshots.isNotEmpty())
            assertJarIsDeterministic(firstJar)

            val cached = compiler.compile(firstModule)

            assertSuccessful(cached)
            assertTrue(cached.cacheHit)
            assertEquals(firstJar, cached.generationJar)
            assertEquals(firstSources, compiler.generatedSourcesDirectory.fileTimes())
            assertEquals(firstSnapshots, compiler.classpathSnapshotsDirectory.fileTimes())

            val requiredFacade = firstModule.facadeClasses().getValue("message")
            firstJar.removeEntry(requiredFacade)
            val repaired = compiler.compile(firstModule)

            assertSuccessful(repaired)
            assertFalse(repaired.cacheHit)
            assertContentEquals(firstJarBytes, Files.readAllBytes(firstJar))

            val unchangedTimestamp = FileTime.fromMillis(1_234_567_890_000L)
            firstModule.files.forEach { source ->
                Files.setLastModifiedTime(
                    compiler.generatedSourcesDirectory.resolve(source.name),
                    unchangedTimestamp
                )
            }
            val secondModule = module(message = "v2")
            val second = compiler.compile(secondModule)

            assertSuccessful(second)
            assertFalse(second.cacheHit)
            val secondJar = assertNotNull(second.generationJar)
            assertNotEquals(firstJar, secondJar)
            assertTrue(firstJar.isRegularFile())
            assertContentEquals(firstJarBytes, Files.readAllBytes(firstJar))
            assertEquals(firstSnapshots, compiler.classpathSnapshotsDirectory.fileTimes())

            val firstFacades = firstModule.facadeClasses()
            val secondFacades = secondModule.facadeClasses()
            assertNotEquals(
                firstClasses.getValue(firstFacades.getValue("message")),
                compiler.classesDirectory.fileHashes()
                    .getValue(secondFacades.getValue("message"))
            )
            assertEquals(
                firstClasses.getValue(firstFacades.getValue("consumer")),
                compiler.classesDirectory.fileHashes()
                    .getValue(secondFacades.getValue("consumer"))
            )
            assertEquals(
                firstClasses.getValue(firstFacades.getValue("independent")),
                compiler.classesDirectory.fileHashes()
                    .getValue(secondFacades.getValue("independent"))
            )

            secondModule.files
                .filterNot { source -> source.text.contains("fun message") }
                .forEach { source ->
                    assertEquals(
                        unchangedTimestamp,
                        Files.getLastModifiedTime(
                            compiler.generatedSourcesDirectory.resolve(source.name)
                        ),
                        "Unchanged generated source was rewritten: ${source.name}"
                    )
                }

            val deterministicBytes = Files.readAllBytes(secondJar)
            Files.delete(secondJar)
            val repackaged = compiler.compile(secondModule)

            assertSuccessful(repackaged)
            assertFalse(repackaged.cacheHit)
            assertContentEquals(
                deterministicBytes,
                Files.readAllBytes(assertNotNull(repackaged.generationJar))
            )

            val successfulClasses = compiler.classesDirectory.fileHashes()
            val failed = compiler.compile(brokenModule())

            assertEquals(CompilationResult.COMPILATION_ERROR, failed.result)
            assertFalse(failed.cacheHit)
            assertNull(failed.generationJar)
            assertEquals(successfulClasses, compiler.classesDirectory.fileHashes())
            val diagnostic = assertNotNull(
                failed.diagnostics.firstOrNull { item ->
                    item.isError && item.sourceName == "message.kt"
                },
                failed.diagnostics.joinToString("\n")
            )
            assertEquals(4, diagnostic.line)
            assertTrue(diagnostic.column != null && diagnostic.column > 0)

            val failedAgain = compiler.compile(brokenModule())

            assertEquals(CompilationResult.COMPILATION_ERROR, failedAgain.result)
            assertFalse(failedAgain.cacheHit)
            assertNull(failedAgain.generationJar)
            assertEquals(successfulClasses, compiler.classesDirectory.fileHashes())

            val recovered = compiler.compile(module(message = "v3"))

            assertSuccessful(recovered)
            assertFalse(recovered.cacheHit)
            val recoveredJar = assertNotNull(recovered.generationJar)
            val snapshotsBeforeCleanup =
                compiler.classpathSnapshotsDirectory.directFiles()
            val oldestSnapshot = snapshotsBeforeCleanup.first()
            Files.setLastModifiedTime(oldestSnapshot, FileTime.fromMillis(0L))

            val cleanup = compiler.pruneCaches(
                retainedGenerationJars = setOf(recoveredJar),
                maxGenerationJars = 1,
                maxClasspathSnapshots =
                    (snapshotsBeforeCleanup.size - 1).coerceAtLeast(0),
                maxAge = Duration.ofDays(36_500)
            )

            assertTrue(cleanup.failures.isEmpty(), cleanup.failures.toString())
            assertTrue(firstJar in cleanup.removedGenerationJars)
            assertTrue(secondJar in cleanup.removedGenerationJars)
            assertTrue(oldestSnapshot in cleanup.removedClasspathSnapshots)
            assertFalse(firstJar.isRegularFile())
            assertFalse(secondJar.isRegularFile())
            assertTrue(recoveredJar.isRegularFile())

            val afterSnapshotPrune = compiler.compile(module(message = "v4"))

            assertSuccessful(afterSnapshotPrune)
            assertFalse(afterSnapshotPrune.cacheHit)
            assertTrue(oldestSnapshot.isRegularFile())
        }
    }

    private fun withCompiler(
        block: (KotlinIncrementalProjectCompiler) -> Unit
    ) {
        val root = Files.createTempDirectory("eternal-script-incremental-test")
        try {
            val classpath = classpathFromClassloader(javaClass.classLoader)
                .orEmpty()
                .map(File::toPath)
                .filter(Files::exists)
                .distinct()
            block(
                KotlinIncrementalProjectCompiler(
                    cacheRoot = root,
                    classpath = classpath,
                    implementationClassLoader = javaClass.classLoader
                )
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun module(message: String): ScriptProjectModule =
        ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "message.kt",
                    """
                    package probe.provider

                    private fun render(value: String): String = value
                    internal fun message(): String = render("$message")
                    """.trimIndent()
                ),
                ScriptProjectFile(
                    "consumer.kt",
                    """
                    package probe.consumer

                    import probe.provider.message

                    internal fun consume(): String = message()
                    """.trimIndent()
                ),
                ScriptProjectFile(
                    "independent.kt",
                    """
                    package probe.independent

                    private fun hidden(): Int = 7
                    internal fun independent(): Int = hidden()
                    """.trimIndent()
                )
            )
        ).module

    private fun brokenModule(): ScriptProjectModule =
        ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "message.kt",
                    """
                    package probe.provider

                    internal fun message(): String =
                        missingFromProject
                    """.trimIndent()
                ),
                ScriptProjectFile(
                    "consumer.kt",
                    """
                    package probe.consumer

                    import probe.provider.message

                    internal fun consume(): String = message()
                    """.trimIndent()
                ),
                ScriptProjectFile(
                    "independent.kt",
                    """
                    package probe.independent

                    private fun hidden(): Int = 7
                    internal fun independent(): Int = hidden()
                    """.trimIndent()
                )
            )
        ).module

    private fun nestedModule(
        alphaPath: String,
        alphaValue: String,
        includeBeta: Boolean
    ): ScriptProjectModule =
        ScriptProjectSource.compose(
            buildList {
                add(
                    ScriptProjectFile(
                        alphaPath,
                        """
                        package probe.alpha

                        internal fun alpha(): String = "$alphaValue"
                        """.trimIndent()
                    )
                )
                if (includeBeta) {
                    add(
                        ScriptProjectFile(
                            "nested/beta/shared.kt",
                            """
                            package probe.beta

                            internal fun beta(): String = "beta"
                            """.trimIndent()
                        )
                    )
                }
                add(
                    ScriptProjectFile(
                        "nested/stable/shared.kt",
                        """
                        package probe.stable

                        internal fun stable(): String = "stable"
                        """.trimIndent()
                    )
                )
            }
        ).module

    private fun ScriptProjectModule.facadeClasses(): Map<String, String> =
        mapOf(
            "message" to facadeClass("fun message"),
            "consumer" to facadeClass("fun consume"),
            "independent" to facadeClass("fun independent")
        )

    private fun ScriptProjectModule.facadeClass(sourceMarker: String): String =
        assertNotNull(
            files.single { source -> source.text.contains(sourceMarker) }.facadeClassName
        ).replace('.', '/') + ".class"

    private fun assertSuccessful(result: KotlinIncrementalProjectCompilation) {
        assertTrue(
            result.isSuccess,
            result.diagnostics.joinToString("\n") { diagnostic ->
                "${diagnostic.sourceName}:${diagnostic.line}:${diagnostic.column} " +
                    diagnostic.message
            }
        )
        assertEquals(CompilationResult.COMPILATION_SUCCESS, result.result)
        assertNotNull(result.generationJar)
    }

    private fun assertJarIsDeterministic(jarPath: Path) {
        JarFile(jarPath.toFile(), false).use { jar ->
            val entries = jar.entries().asSequence().toList()
            val names = entries.map { entry -> entry.name }

            assertEquals(names.sorted(), names)
            entries.forEach { entry ->
                assertEquals(0L, entry.time)
            }
        }
    }
}

private fun Path.fileTimes(): Map<String, FileTime> =
    Files.walk(this).use { paths ->
        paths.filter(Path::isRegularFile)
            .sorted()
            .toList()
            .associate { path ->
                relativize(path).invariantSeparatorsPathString to
                    Files.getLastModifiedTime(path)
            }
    }

private fun Path.fileHashes(): Map<String, String> =
    Files.walk(this).use { paths ->
        paths.filter(Path::isRegularFile)
            .sorted()
            .toList()
            .associate { path ->
                relativize(path).invariantSeparatorsPathString to
                    HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path))
                    )
            }
    }

private fun Path.directFiles(): List<Path> =
    Files.list(this).use { paths ->
        paths.filter(Path::isRegularFile)
            .sorted()
            .toList()
    }

private fun Path.removeEntry(entryName: String) {
    val entries = JarFile(toFile(), false).use { jar ->
        jar.entries().asSequence()
            .filterNot { entry -> entry.name == entryName }
            .map { entry ->
                JarEntry(entry.name) to jar.getInputStream(entry).use { input ->
                    input.readAllBytes()
                }
            }
            .toList()
    }
    val temporary = Files.createTempFile(parent, ".corrupt-artifact.", ".jar")
    try {
        JarOutputStream(Files.newOutputStream(temporary)).use { output ->
            entries.forEach { (entry, bytes) ->
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        Files.move(temporary, this, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
