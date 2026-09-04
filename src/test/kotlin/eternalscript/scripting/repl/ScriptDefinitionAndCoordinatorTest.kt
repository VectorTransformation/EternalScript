package eternalscript.scripting.repl

import eternalscript.api.script.Script
import eternalscript.scripting.compilation.ScriptCompilationCoordinator
import eternalscript.scripting.compilation.ScriptCompilationEnvironmentFactory
import eternalscript.scripting.compilation.ScriptCompilationOutcome
import eternalscript.scripting.compilation.ScriptCandidateSelection
import eternalscript.scripting.compilation.ScriptEnvironmentSnapshot
import eternalscript.scripting.repl.k2.CompiledComponent
import eternalscript.scripting.repl.k2.CompiledComponentArtifact
import eternalscript.scripting.repl.k2.CompiledComponentGeneration
import eternalscript.scripting.repl.k2.ScriptComponent
import eternalscript.scripting.repl.k2.ScriptDependencyGraph
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ScriptDefinitionAndCoordinatorTest {
    @Test
    fun `exposes only the supported lifecycle event and command DSL names`() {
        val methods = Script::class.java.declaredMethods.map { method -> method.name }.toSet()

        assertTrue("onLoad" in methods)
        assertTrue("onUnload" in methods)
        assertTrue("onDispose" in methods)
        assertTrue("own" in methods)
        assertTrue("on" in methods)
        assertTrue("command" in methods)
        assertFalse("event" in methods)
        assertFalse("register" in methods)
    }

    @Test
    fun `clear config and disable epochs invalidate late compilation tokens`() {
        val directory = createTempDirectory("eternalscript-epoch-test").toFile()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> block() }
        try {
            val snapshot = environmentSnapshot()
            val beforeClear = coordinator.request(0, emptyList(), emptyList(), environmentSnapshot = snapshot)
            assertTrue(coordinator.isCurrent(beforeClear))
            coordinator.invalidate()
            assertFalse(coordinator.isCurrent(beforeClear))

            val beforeConfig = coordinator.request(0, emptyList(), emptyList(), environmentSnapshot = snapshot)
            assertTrue(coordinator.isCurrent(beforeConfig))
            coordinator.invalidate(environment = true)
            assertFalse(coordinator.isCurrent(beforeConfig))

            val beforeDisable = coordinator.request(0, emptyList(), emptyList(), environmentSnapshot = snapshot)
            assertTrue(coordinator.isCurrent(beforeDisable))
            coordinator.close()
            assertFalse(coordinator.isCurrent(beforeDisable))
        } finally {
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `disable discards a result that was queued for the main thread`() {
        val directory = createTempDirectory("eternalscript-late-result-test").toFile()
        val dispatched = CompletableFuture<() -> Unit>()
        val callbackInvoked = AtomicBoolean()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> dispatched.complete(block) }
        try {
            val request = coordinator.request(
                0,
                emptyList(),
                emptyList(),
                environmentSnapshot = environmentSnapshot()
            )
            coordinator.invalidate()
            coordinator.compileAsync(request) { callbackInvoked.set(true) }
            val lateMainTask = dispatched.get(10, TimeUnit.SECONDS)

            coordinator.close()
            lateMainTask()

            assertFalse(callbackInvoked.get())
        } finally {
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `main dispatcher failure is reported instead of leaving an async request pending`() {
        val directory = createTempDirectory("eternalscript-dispatch-failure-test").toFile()
        val callbackInvoked = AtomicBoolean()
        val dispatchFailure = CompletableFuture<Throwable>()
        val coordinator = ScriptCompilationCoordinator(directory) {
            throw IllegalStateException("dispatcher unavailable")
        }
        try {
            val request = coordinator.request(
                0,
                emptyList(),
                emptyList(),
                environmentSnapshot = environmentSnapshot()
            )

            coordinator.compileAsync(
                request,
                dispatchFailure = dispatchFailure::complete,
                callback = { callbackInvoked.set(true) }
            )

            assertEquals("dispatcher unavailable", dispatchFailure.get(10, TimeUnit.SECONDS).message)
            assertFalse(callbackInvoked.get())
        } finally {
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cache executor rejection releases the retained generation`() {
        val directory = createTempDirectory("eternalscript-cache-rejection-test").toFile()
        val compilerExecutor = Executors.newSingleThreadExecutor()
        val cacheExecutor = Executors.newSingleThreadExecutor().apply { shutdown() }
        val coordinator = ScriptCompilationCoordinator(
            cacheRoot = directory,
            mainDispatcher = { block -> block() },
            compilerExecutor = compilerExecutor,
            cacheExecutor = cacheExecutor
        )
        val artifact = CompiledComponentArtifact.create(
            directory.toPath().resolve("live-components"),
            "cache-rejection",
            emptyMap()
        )
        val generation = generationOf("cache-rejection", artifact)
        val environment = ScriptCompilationEnvironmentFactory.build(environmentSnapshot())
        val reported = CompletableFuture<Throwable>()
        try {
            coordinator.publishCache(generation, environment, reported::complete)
            assertIs<RejectedExecutionException>(reported.get(10, TimeUnit.SECONDS))

            generation.close()

            assertFalse(artifact.jar.toFile().exists())
        } finally {
            generation.close()
            environment.close()
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `disabled cache skips artifact publication`() {
        val directory = createTempDirectory("eternalscript-cache-disabled-test").toFile()
        val coordinator = ScriptCompilationCoordinator(
            cacheRoot = directory,
            cacheEnabled = { false },
            mainDispatcher = { block -> block() }
        )
        val artifact = CompiledComponentArtifact.create(
            directory.toPath().resolve("live-components"),
            "cache-disabled",
            emptyMap()
        )
        val generation = generationOf("cache-disabled", artifact)
        val environment = ScriptCompilationEnvironmentFactory.build(environmentSnapshot())
        val reported = AtomicBoolean()
        try {
            coordinator.publishCache(generation, environment) { reported.set(true) }

            assertFalse(reported.get())
            assertFalse(directory.resolve("objects").exists())
            assertFalse(directory.resolve("index.json").exists())
        } finally {
            generation.close()
            environment.close()
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `close interrupts a permanently blocked executor after the graceful timeout`() {
        val directory = createTempDirectory("eternalscript-close-barrier-test").toFile()
        val compilerExecutor = Executors.newSingleThreadExecutor()
        val cacheExecutor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        compilerExecutor.execute {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                interrupted.set(true)
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val artifactRoot = directory.resolve("live-components").apply { mkdirs() }
        val coordinator = ScriptCompilationCoordinator(
            cacheRoot = directory,
            mainDispatcher = { block -> block() },
            compilerExecutor = compilerExecutor,
            cacheExecutor = cacheExecutor,
            closeTimeoutMillis = 100
        )
        try {
            coordinator.close()

            assertTrue(interrupted.get())
            assertTrue(compilerExecutor.isTerminated)
            assertTrue(cacheExecutor.isTerminated)
            assertFalse(artifactRoot.exists())
        } finally {
            compilerExecutor.shutdownNow()
            cacheExecutor.shutdownNow()
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `close eventually cleans up after an executor terminates beyond the forced timeout`() {
        val directory = createTempDirectory("eternalscript-close-nonterminating-test").toFile()
        val compilerExecutor = Executors.newSingleThreadExecutor()
        val cacheExecutor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = AtomicBoolean()
        compilerExecutor.execute {
            entered.countDown()
            while (!release.get()) {
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {}
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val artifactRoot = directory.resolve("live-components").apply { mkdirs() }
        val coordinator = ScriptCompilationCoordinator(
            cacheRoot = directory,
            mainDispatcher = { block -> block() },
            compilerExecutor = compilerExecutor,
            cacheExecutor = cacheExecutor,
            closeTimeoutMillis = 25
        )
        try {
            coordinator.close()

            assertFalse(compilerExecutor.isTerminated)
            assertTrue(cacheExecutor.isTerminated)
            assertTrue(artifactRoot.exists())

            release.set(true)
            assertTrue(compilerExecutor.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(waitUntil(10, TimeUnit.SECONDS) { !artifactRoot.exists() })
        } finally {
            release.set(true)
            compilerExecutor.shutdownNow()
            cacheExecutor.shutdownNow()
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `forced shutdown releases a retained cache task removed from the queue`() {
        val directory = createTempDirectory("eternalscript-cache-forced-shutdown-test").toFile()
        val compilerExecutor = Executors.newSingleThreadExecutor()
        val cacheExecutor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        cacheExecutor.execute {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {}
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val coordinator = ScriptCompilationCoordinator(
            cacheRoot = directory,
            mainDispatcher = { block -> block() },
            compilerExecutor = compilerExecutor,
            cacheExecutor = cacheExecutor,
            closeTimeoutMillis = 100
        )
        val artifact = CompiledComponentArtifact.create(
            directory.toPath().resolve("live-components"),
            "queued-cache",
            emptyMap()
        )
        val generation = generationOf("queued-cache", artifact)
        val environment = ScriptCompilationEnvironmentFactory.build(environmentSnapshot())
        try {
            coordinator.publishCache(generation, environment) {}
            coordinator.close()

            generation.close()

            assertTrue(cacheExecutor.isTerminated)
            assertFalse(Files.exists(artifact.jar))
        } finally {
            generation.close()
            environment.close()
            compilerExecutor.shutdownNow()
            cacheExecutor.shutdownNow()
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `close preserves an artifact retained by an in flight runtime context`() {
        val directory = createTempDirectory("eternalscript-close-retained-artifact-test").toFile()
        val artifactRoot = directory.resolve("live-components").toPath()
        val artifact = CompiledComponentArtifact.create(artifactRoot, "in-flight", emptyMap())
        val runtimeGeneration = generationOf("in-flight", artifact)
        val inFlightExecution = runtimeGeneration.retained()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> block() }
        try {
            runtimeGeneration.close()
            coordinator.close()

            assertTrue(Files.exists(artifact.jar))
            assertTrue(Files.exists(artifactRoot))

            inFlightExecution.close()
            assertFalse(Files.exists(artifact.jar))
        } finally {
            runtimeGeneration.close()
            inFlightExecution.close()
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `compiler environment uses only curated defaults while retaining runtime roots on classpath`() {
        val stdlib = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI())
        val pluginArtifact = File(Script::class.java.protectionDomain.codeSource.location.toURI())
        val environment = ScriptCompilationEnvironmentFactory.build(
            ScriptEnvironmentSnapshot(
                Script::class.java.classLoader,
                emptyList(),
                emptyList(),
                "1.0.0",
                pluginArtifact,
                listOf(stdlib)
            )
        )

        assertTrue(environment.classpath.any { file -> file.absoluteFile.normalize() == stdlib.absoluteFile.normalize() })
        assertTrue(environment.defaultImports.isEmpty())
        environment.close()
    }

    @Test
    fun `environment replacement is not interrupted by previous environment cleanup failure`() {
        val directory = createTempDirectory("eternalscript-environment-replacement-test").toFile()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> block() }
        try {
            val first = coordinator.environmentBlocking(environmentSnapshot()).getOrThrow()
            first.close()

            val replacement = coordinator.environmentBlocking(
                environmentSnapshot().copy(pluginVersion = "2.0.0")
            )

            assertTrue(replacement.isSuccess)
        } finally {
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `load selects declaration providers without unrelated import only files`() {
        val directory = createTempDirectory("eternalscript-load-closure").toFile()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> block() }
        var generation: eternalscript.scripting.repl.k2.CompiledComponentGeneration? = null
        try {
            val sources = listOf(
                SharedReplSource("00-provider.eternal.kts", "val base: Int = 41"),
                SharedReplSource("10-consumer.eternal.kts", "val answer: Int = base + 1"),
                SharedReplSource("20-independent.eternal.kts", "val independent: Int = 7"),
                SharedReplSource("30-imports.eternal.kts", "import java.math.BigInteger")
            )
            val request = coordinator.request(
                activeRevision = 0,
                activeSources = emptyList(),
                candidateSources = sources,
                selection = ScriptCandidateSelection.Load(setOf("10-consumer.eternal.kts"), emptySet()),
                environmentSnapshot = compilationEnvironmentSnapshot()
            )
            val outcome = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(request)
            )
            generation = outcome.generation
            assertEquals(
                setOf(
                    "00-provider.eternal.kts",
                    "10-consumer.eternal.kts"
                ),
                outcome.candidateSources.map(SharedReplSource::name).toSet()
            )
            assertFalse(outcome.candidateSources.any { it.name == "20-independent.eternal.kts" })
            assertFalse(outcome.candidateSources.any { it.name == "30-imports.eternal.kts" })
        } finally {
            generation?.close()
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `load accepts multiple folder targets and includes every provider closure`() {
        val directory = createTempDirectory("eternalscript-load-folder-closure").toFile()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> block() }
        var generation: eternalscript.scripting.repl.k2.CompiledComponentGeneration? = null
        try {
            val sources = listOf(
                SharedReplSource("00-provider.eternal.kts", "val base: Int = 41"),
                SharedReplSource("folder/10-consumer.eternal.kts", "val answer: Int = base + 1"),
                SharedReplSource("folder/20-local.eternal.kts", "val local: Int = 7"),
                SharedReplSource("outside.eternal.kts", "val outside: Int = 9")
            )
            val request = coordinator.request(
                activeRevision = 0,
                activeSources = emptyList(),
                candidateSources = sources,
                selection = ScriptCandidateSelection.Load(
                    setOf("folder/10-consumer.eternal.kts", "folder/20-local.eternal.kts"),
                    emptySet()
                ),
                environmentSnapshot = compilationEnvironmentSnapshot()
            )

            val outcome = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(request)
            )
            generation = outcome.generation
            assertEquals(
                setOf(
                    "00-provider.eternal.kts",
                    "folder/10-consumer.eternal.kts",
                    "folder/20-local.eternal.kts"
                ),
                outcome.candidateSources.map(SharedReplSource::name).toSet()
            )
            assertFalse(outcome.candidateSources.any { it.name == "outside.eternal.kts" })
        } finally {
            generation?.close()
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `load forces an unchanged target and its active consumers only`() {
        val directory = createTempDirectory("eternalscript-load-force-target").toFile()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> block() }
        val generations = mutableListOf<eternalscript.scripting.repl.k2.CompiledComponentGeneration>()
        try {
            val sources = listOf(
                SharedReplSource("00-provider.eternal.kts", "val base: Int = 41"),
                SharedReplSource("10-consumer.eternal.kts", "val answer: Int = base + 1"),
                SharedReplSource("20-independent.eternal.kts", "val independent: Int = 7")
            )
            val snapshot = compilationEnvironmentSnapshot()
            val cold = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(
                    coordinator.request(
                        activeRevision = 0,
                        activeSources = emptyList(),
                        candidateSources = sources,
                        environmentSnapshot = snapshot
                    )
                )
            )
            generations += cold.generation
            coordinator.commit(cold, 1)

            val forced = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(
                    coordinator.request(
                        activeRevision = 1,
                        activeSources = sources,
                        candidateSources = sources,
                        selection = ScriptCandidateSelection.Load(
                            setOf("00-provider.eternal.kts"),
                            sources.mapTo(linkedSetOf(), SharedReplSource::name)
                        ),
                        environmentSnapshot = snapshot
                    )
                )
            )
            generations += forced.generation
            assertEquals(2, forced.metrics.compiledCount)
            assertEquals(1, forced.metrics.reusedCount)
            assertEquals(
                setOf("00-provider.eternal.kts", "10-consumer.eternal.kts"),
                forced.affectedPaths.toSet()
            )
            assertFalse("20-independent.eternal.kts" in forced.affectedPaths)
        } finally {
            generations.asReversed().forEach { generation -> generation.close() }
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `committed compiler generation is aligned before the next request`() {
        val directory = createTempDirectory("eternalscript-coordinator-alignment").toFile()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> block() }
        var firstGeneration: eternalscript.scripting.repl.k2.CompiledComponentGeneration? = null
        var secondGeneration: eternalscript.scripting.repl.k2.CompiledComponentGeneration? = null
        try {
            val firstSources = listOf(
                SharedReplSource("00-remove.eternal.kts", "val removed: Int = 1"),
                SharedReplSource("10-keep.eternal.kts", "val kept: Int = 2")
            )
            val snapshot = compilationEnvironmentSnapshot()
            val first = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(
                    coordinator.request(0, emptyList(), firstSources, environmentSnapshot = snapshot)
                )
            )
            firstGeneration = first.generation
            coordinator.commit(first, 1)

            val second = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(
                    coordinator.request(
                        1,
                        firstSources,
                        firstSources.drop(1),
                        environmentSnapshot = snapshot
                    )
                )
            )
            secondGeneration = second.generation
            assertEquals(0, second.metrics.compiledCount)
            assertEquals(1, second.metrics.reusedCount)
        } finally {
            secondGeneration?.close()
            firstGeneration?.close()
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unchanged exact request skips analysis and compilation`() {
        val directory = createTempDirectory("eternalscript-coordinator-unchanged").toFile()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> block() }
        val generations = mutableListOf<eternalscript.scripting.repl.k2.CompiledComponentGeneration>()
        try {
            val sources = listOf(SharedReplSource("value.eternal.kts", "val value: Int = 1"))
            val snapshot = compilationEnvironmentSnapshot()
            val first = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(
                    coordinator.request(0, emptyList(), sources, environmentSnapshot = snapshot)
                )
            )
            generations += first.generation
            coordinator.commit(first, 1)

            val unchanged = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(
                    coordinator.request(1, sources, sources, environmentSnapshot = snapshot)
                )
            )
            generations += unchanged.generation

            assertTrue(unchanged.affectedPaths.isEmpty())
            assertEquals(0, unchanged.metrics.analyzedCount)
            assertEquals(0, unchanged.metrics.compiledCount)
            assertEquals(1, unchanged.metrics.reusedCount)
            assertEquals(0, unchanged.metrics.compileMillis)
        } finally {
            generations.asReversed().forEach { generation -> generation.close() }
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `same fingerprint with a replacement plugin loader rebuilds compiler state`() {
        val directory = createTempDirectory("eternalscript-loader-identity").toFile()
        val coordinator = ScriptCompilationCoordinator(directory) { block -> block() }
        val parent = Script::class.java.classLoader
        val firstLoader = URLClassLoader(emptyArray(), parent)
        val secondLoader = URLClassLoader(emptyArray(), parent)
        var firstGeneration: eternalscript.scripting.repl.k2.CompiledComponentGeneration? = null
        var secondGeneration: eternalscript.scripting.repl.k2.CompiledComponentGeneration? = null
        try {
            val sources = listOf(SharedReplSource("value.eternal.kts", "val value: Int = 1"))
            val baseSnapshot = compilationEnvironmentSnapshot()
            val firstSnapshot = baseSnapshot.copy(pluginClassLoaders = listOf(firstLoader))
            val secondSnapshot = baseSnapshot.copy(pluginClassLoaders = listOf(secondLoader))
            val firstEnvironment = ScriptCompilationEnvironmentFactory.build(firstSnapshot)
            val secondEnvironment = ScriptCompilationEnvironmentFactory.build(secondSnapshot)
            try {
                assertEquals(firstEnvironment.fingerprint, secondEnvironment.fingerprint)
                assertFalse(firstEnvironment.hasSameClassLoaderIdentity(secondEnvironment))
            } finally {
                secondEnvironment.close()
                firstEnvironment.close()
            }

            val first = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(
                    coordinator.request(0, emptyList(), sources, environmentSnapshot = firstSnapshot)
                )
            )
            firstGeneration = first.generation
            coordinator.commit(first, 1)

            val second = assertIs<ScriptCompilationOutcome.Success>(
                coordinator.compileBlocking(
                    coordinator.request(1, sources, sources, environmentSnapshot = secondSnapshot)
                )
            )
            secondGeneration = second.generation
            assertEquals(1, second.metrics.compiledCount)
            assertEquals(0, second.metrics.reusedCount)
        } finally {
            secondGeneration?.close()
            firstGeneration?.close()
            coordinator.close()
            secondLoader.close()
            firstLoader.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `compiler classpath keeps runtime plugin order before script libraries`() {
        val directory = createTempDirectory("eternalscript-classpath-order")
        val firstPluginJar = directory.resolve("z-first-plugin.jar").toFile()
        val secondPluginJar = directory.resolve("a-second-plugin.jar").toFile()
        val scriptLibraryJar = directory.resolve("00-script-library.jar").toFile()
        writeProbeJar(firstPluginJar, 1)
        writeProbeJar(secondPluginJar, 2)
        writeProbeJar(scriptLibraryJar, 3)
        val parent = Script::class.java.classLoader
        val firstPluginLoader = URLClassLoader(arrayOf(firstPluginJar.toURI().toURL()), parent)
        val secondPluginLoader = URLClassLoader(arrayOf(secondPluginJar.toURI().toURL()), parent)
        try {
            val environment = ScriptCompilationEnvironmentFactory.build(
                ScriptEnvironmentSnapshot(
                    baseClassLoader = parent,
                    pluginClassLoaders = listOf(firstPluginLoader, secondPluginLoader),
                    libraryRoots = listOf(scriptLibraryJar),
                    pluginVersion = "1.0.0",
                    pluginArtifact = null
                )
            )
            try {
                val classpath = environment.classpath.map { file -> file.absoluteFile.normalize() }
                val firstIndex = classpath.indexOf(firstPluginJar.absoluteFile.normalize())
                val secondIndex = classpath.indexOf(secondPluginJar.absoluteFile.normalize())
                val libraryIndex = classpath.indexOf(scriptLibraryJar.absoluteFile.normalize())
                assertTrue(firstIndex >= 0)
                assertTrue(secondIndex > firstIndex)
                assertTrue(libraryIndex > secondIndex)
            } finally {
                environment.close()
            }
        } finally {
            secondPluginLoader.close()
            firstPluginLoader.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `environment fingerprint changes when a library is replaced at the same path`() {
        val directory = createTempDirectory("eternalscript-environment-content")
        val library = directory.resolve("library.jar")
        writeProbeJar(library.toFile(), 1)
        val snapshot = ScriptEnvironmentSnapshot(
            Script::class.java.classLoader,
            emptyList(),
            listOf(library.toFile()),
            "1.0.0",
            null
        )
        val first = ScriptCompilationEnvironmentFactory.build(snapshot)
        try {
            writeProbeJar(library.toFile(), 2)
            val second = ScriptCompilationEnvironmentFactory.build(snapshot)
            try {
                assertNotEquals(first.fingerprint, second.fingerprint)
            } finally {
                second.close()
            }
        } finally {
            first.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `coordinator reuses an unchanged environment and rebuilds after a library change`() {
        val directory = createTempDirectory("eternalscript-environment-reuse")
        val library = directory.resolve("library.jar")
        writeProbeJar(library.toFile(), 1)
        val snapshot = ScriptEnvironmentSnapshot(
            Script::class.java.classLoader,
            emptyList(),
            listOf(library.toFile()),
            "1.0.0",
            null
        )
        val coordinator = ScriptCompilationCoordinator(directory.toFile()) { block -> block() }
        try {
            val first = coordinator.environmentBlocking(snapshot).getOrThrow()
            val unchanged = coordinator.environmentBlocking(snapshot).getOrThrow()
            assertSame(first, unchanged)

            writeProbeJar(library.toFile(), 2)
            val changed = coordinator.environmentBlocking(snapshot).getOrThrow()
            assertNotEquals(first.fingerprint, changed.fingerprint)
        } finally {
            coordinator.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun environmentSnapshot(): ScriptEnvironmentSnapshot = ScriptEnvironmentSnapshot(
        Script::class.java.classLoader,
        emptyList(),
        emptyList(),
        "1.0.0",
        null
    )

    private fun compilationEnvironmentSnapshot(): ScriptEnvironmentSnapshot {
        val classpath = System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .map(::File)
            .filter(File::exists)
        return ScriptEnvironmentSnapshot(
            Script::class.java.classLoader,
            emptyList(),
            emptyList(),
            "1.0.0",
            File(Script::class.java.protectionDomain.codeSource.location.toURI()),
            classpath
        )
    }

    private fun generationOf(
        componentId: String,
        artifact: CompiledComponentArtifact
    ): CompiledComponentGeneration {
        val component = ScriptComponent(componentId, emptyList(), emptySet())
        return CompiledComponentGeneration(
            graph = ScriptDependencyGraph(
                paths = emptyList(),
                dependencies = emptyMap(),
                initializationDependencies = emptyMap(),
                components = listOf(component),
                initializationOrder = emptyList()
            ),
            sources = emptyList(),
            components = mapOf(
                componentId to CompiledComponent(component, emptyList(), emptyList(), artifact)
            )
        )
    }

    private fun waitUntil(timeout: Long, unit: TimeUnit, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun writeProbeJar(target: File, marker: Int) {
        JarOutputStream(target.outputStream()).use { output ->
            output.putNextEntry(JarEntry("probe/Version.class"))
            output.write(byteArrayOf(marker.toByte()))
            output.closeEntry()
        }
    }
}
