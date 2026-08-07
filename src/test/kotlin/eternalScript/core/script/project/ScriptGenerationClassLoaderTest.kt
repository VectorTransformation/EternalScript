package eternalScript.core.script.project

import eternalScript.api.script.EternalScript
import eternalScript.core.script.classloading.DisabledScriptPluginClassException
import eternalScript.core.script.classloading.ScriptGenerationClassLoader
import eternalScript.core.script.classloading.ScriptGenerationRegistry
import eternalScript.core.script.classpath.ScriptClassIdentityConflictException
import eternalScript.core.script.classpath.ScriptPluginClasspathPlugin
import eternalScript.core.script.classpath.ScriptPluginClasspathSnapshot
import eternalScript.core.script.classpath.ownedClassLoaders
import eternalScript.core.script.generation.GenerationRuntimeHandle
import eternalScript.core.script.runtime.ManagedScriptRuntime
import pluginfixtures.identity.DuplicateApi
import net.kyori.pluginfixture.PluginOnlyApi
import scriptfixtures.GeneratedReference
import scriptfixtures.ParentFirstGeneratedReference
import java.lang.ref.WeakReference
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptGenerationClassLoaderTest {
    @Test
    fun `generation handle binds every Script and closes shared resources once`() {
        val parent = DuplicateApi::class.java.classLoader
        val loader = ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot(parent),
            emptySet(),
            emptySet()
        )
        val jar = testJar()
        val runtimes = listOf(
            ManagedScriptRuntime(object : EternalScript() {}),
            ManagedScriptRuntime(object : EternalScript() {})
        )
        var retains = 0
        var releases = 0
        val handle = GenerationRuntimeHandle(
            loader,
            jar,
            runtimes,
            retainGenerationJar = { retains += 1 },
            releaseGenerationJar = { releases += 1 }
        )

        try {
            runtimes.forEach { runtime ->
                assertSame(loader, runtime.executionGate.contextClassLoader())
            }
            assertEquals(1, retains)
            assertEquals(0, releases)
        } finally {
            handle.close()
            handle.close()
            Files.deleteIfExists(jar)
        }

        runtimes.forEach { runtime ->
            assertNull(runtime.executionGate.contextClassLoader())
        }
        assertEquals(1, releases)
    }

    @Test
    fun `generation handle detaches every Script when jar release fails`() {
        val parent = DuplicateApi::class.java.classLoader
        val loader = ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot(parent),
            emptySet(),
            emptySet()
        )
        val jar = testJar()
        val runtimes = listOf(
            ManagedScriptRuntime(object : EternalScript() {}),
            ManagedScriptRuntime(object : EternalScript() {})
        )
        val handle = GenerationRuntimeHandle(
            loader,
            jar,
            runtimes,
            retainGenerationJar = {},
            releaseGenerationJar = { error("release failed") }
        )

        try {
            val failure = assertFailsWith<IllegalStateException> {
                handle.close()
            }

            assertEquals("release failed", failure.message)
            runtimes.forEach { runtime ->
                assertNull(runtime.executionGate.contextClassLoader())
            }
            handle.close()
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `generation registry invalidates a handle without a first Script anchor`() {
        val bytes = classBytes(DuplicateApi::class.java)
        val parent = DuplicateApi::class.java.classLoader
        val defining = IsolatedClassLoader(DuplicateApi::class.java.name, bytes, parent)
        val forwardingParent = PassthroughClassLoader(defining)
        val loader = ScriptGenerationClassLoader(
            emptyArray(),
            forwardingParent,
            snapshot(forwardingParent, plugin("Alpha", defining)),
            emptySet(),
            setOf("Alpha")
        )
        val jar = testJar()
        val handle = GenerationRuntimeHandle(
            loader,
            jar,
            listOf(
                ManagedScriptRuntime(object : EternalScript() {}),
                ManagedScriptRuntime(object : EternalScript() {})
            ),
            retainGenerationJar = {},
            releaseGenerationJar = {}
        )

        try {
            assertEquals(setOf("Alpha"), handle.pluginDependencies)
            ScriptGenerationRegistry.invalidate("alpha")
            assertFailsWith<DisabledScriptPluginClassException> {
                loader.loadClass(DuplicateApi::class.java.name)
            }
        } finally {
            handle.close()
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `duplicate JetBrains annotation identities are tolerated`() {
        val annotationType = Class.forName("org.jetbrains.annotations.NotNull")
        val className = annotationType.name
        val parent = checkNotNull(annotationType.classLoader)
        val bytes = classBytes(annotationType)
        val snapshot = snapshot(
            parent,
            plugin("Alpha", IsolatedClassLoader(className, bytes, parent)),
            plugin("Beta", IsolatedClassLoader(className, bytes, parent))
        )

        ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            assertSame(annotationType, loader.loadClass(className))
            assertTrue(snapshot.conflicts.isEmpty())
        }
    }

    @Test
    fun `same delegated class identity is accepted`() {
        val parent = DuplicateApi::class.java.classLoader
        val first = PassthroughClassLoader(parent)
        val second = PassthroughClassLoader(parent)
        val snapshot = snapshot(
            parent,
            plugin("Alpha", first),
            plugin("Beta", second)
        )
        ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            assertSame(DuplicateApi::class.java, loader.loadClass(DuplicateApi::class.java.name))
            assertTrue(loader.pluginDependencies.snapshot().isEmpty())
            assertTrue(snapshot.conflicts.isEmpty())
        }
    }

    @Test
    fun `dynamic different identities fail with plugin diagnostics`() {
        val bytes = classBytes(DuplicateApi::class.java)
        val parent = DuplicateApi::class.java.classLoader
        val first = IsolatedClassLoader(DuplicateApi::class.java.name, bytes, parent)
        val second = IsolatedClassLoader(DuplicateApi::class.java.name, bytes, parent)
        val snapshot = snapshot(
            parent,
            plugin("Alpha", first),
            plugin("Beta", second)
        )
        ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            val failure = assertFailsWith<ScriptClassIdentityConflictException> {
                loader.loadClass(DuplicateApi::class.java.name)
            }
            assertTrue(failure.message.orEmpty().contains(DuplicateApi::class.java.name))
            assertTrue(failure.message.orEmpty().contains("Alpha"))
            assertTrue(failure.message.orEmpty().contains("Beta"))
            assertEquals(1, snapshot.conflicts.size)
        }
    }

    @Test
    fun `runtime parent and plugin identity mismatch is rejected`() {
        val className = DuplicateApi::class.java.name
        val parent = DuplicateApi::class.java.classLoader
        val isolated = IsolatedClassLoader(
            className,
            classBytes(DuplicateApi::class.java),
            parent
        )
        val snapshot = snapshot(parent, plugin("IsolatedApi", isolated))

        ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            val failure = assertFailsWith<ScriptClassIdentityConflictException> {
                loader.loadClass(className)
            }

            assertTrue(failure.message.orEmpty().contains("IsolatedApi"))
            assertTrue(failure.message.orEmpty().contains("<runtime-parent>"))
        }
    }

    @Test
    fun `defining plugin owner is tracked when another plugin delegates to it`() {
        val bytes = classBytes(DuplicateApi::class.java)
        val parent = DenyingClassLoader(
            DuplicateApi::class.java.name,
            DuplicateApi::class.java.classLoader
        )
        val defining = IsolatedClassLoader(DuplicateApi::class.java.name, bytes, parent)
        val forwarding = PassthroughClassLoader(defining)
        val snapshot = snapshot(
            parent,
            plugin("Alpha", defining),
            plugin("Beta", forwarding)
        )
        ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            assertSame(defining.loadClass(DuplicateApi::class.java.name), loader.loadClass(DuplicateApi::class.java.name))
            assertEquals(setOf("Alpha"), loader.pluginDependencies.snapshot())
        }
    }

    @Test
    fun `disabled plugin is rejected even when the core parent can still delegate to it`() {
        val bytes = classBytes(DuplicateApi::class.java)
        val testParent = DuplicateApi::class.java.classLoader
        val defining = IsolatedClassLoader(DuplicateApi::class.java.name, bytes, testParent)
        val forwardingParent = PassthroughClassLoader(defining)
        val snapshot = snapshot(
            forwardingParent,
            plugin("Alpha", defining)
        )
        ScriptGenerationClassLoader(
            emptyArray(),
            forwardingParent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            loader.invalidatePlugin("alpha")

            val failure = assertFailsWith<DisabledScriptPluginClassException> {
                loader.loadClass(DuplicateApi::class.java.name)
            }
            assertTrue(failure.message.orEmpty().contains("Alpha"))
        }
    }

    @Test
    fun `loaded plugin class is rejected after its owner is invalidated`() {
        val className = DuplicateApi::class.java.name
        val parent = DenyingClassLoader(
            className,
            DuplicateApi::class.java.classLoader
        )
        val defining = IsolatedClassLoader(
            className,
            classBytes(DuplicateApi::class.java),
            parent
        )

        ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot(parent, plugin("Alpha", defining)),
            emptySet(),
            emptySet()
        ).use { loader ->
            assertSame(
                defining.loadClass(className),
                Class.forName(className, false, loader)
            )
            assertEquals(setOf("Alpha"), loader.pluginDependencies.snapshot())

            loader.invalidatePlugin("Alpha")

            val failure = assertFailsWith<DisabledScriptPluginClassException> {
                loader.loadClass(className)
            }
            assertTrue(failure.message.orEmpty().contains("Alpha"))
        }
    }

    @Test
    fun `forwarding provider cannot expose class from invalidated defining plugin`() {
        val bytes = classBytes(DuplicateApi::class.java)
        val parent = DenyingClassLoader(
            DuplicateApi::class.java.name,
            DuplicateApi::class.java.classLoader
        )
        val defining = IsolatedClassLoader(DuplicateApi::class.java.name, bytes, parent)
        val forwarding = PassthroughClassLoader(defining)
        val snapshot = snapshot(
            parent,
            plugin("Alpha", defining),
            plugin("Beta", forwarding)
        )
        ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            loader.invalidatePlugin("Alpha")

            val failure = assertFailsWith<DisabledScriptPluginClassException> {
                loader.loadClass(DuplicateApi::class.java.name)
            }
            assertTrue(failure.message.orEmpty().contains("Alpha"))
        }
    }

    @Test
    fun `parent first namespace falls back to actual plugin loader when parent misses`() {
        val className = PluginOnlyApi::class.java.name
        val bytes = classBytes(PluginOnlyApi::class.java)
        val parent = DenyingClassLoader(className, javaClass.classLoader)
        val defining = IsolatedClassLoader(className, bytes, parent)
        val snapshot = snapshot(parent, plugin("AdventureExtension", defining))

        ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            assertSame(defining.loadClass(className), loader.loadClass(className))
            assertEquals(setOf("AdventureExtension"), loader.pluginDependencies.snapshot())
        }
    }

    @Test
    fun `analysis tracks plugin class beneath parent first namespace when parent misses`() {
        val jar = testJar(ParentFirstGeneratedReference::class.java)
        try {
            val className = PluginOnlyApi::class.java.name
            val parent = DenyingClassLoader(
                setOf(className, ParentFirstGeneratedReference::class.java.name),
                javaClass.classLoader
            )
            val defining = IsolatedClassLoader(
                className,
                classBytes(PluginOnlyApi::class.java),
                parent
            )
            val analysis = ScriptClassReferenceAnalyzer.analyze(
                jar,
                snapshot(parent, plugin("AdventureExtension", defining))
            )

            assertTrue(className in analysis.referencedClassNames)
            assertEquals(setOf("AdventureExtension"), analysis.pluginOwnerNames)
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `parent first class delegated from plugin is tracked and invalidated`() {
        val className = PluginOnlyApi::class.java.name
        val testParent = DenyingClassLoader(className, javaClass.classLoader)
        val defining = IsolatedClassLoader(
            className,
            classBytes(PluginOnlyApi::class.java),
            testParent
        )
        val forwardingParent = PassthroughClassLoader(defining)
        val snapshot = snapshot(
            forwardingParent,
            plugin("AdventureExtension", defining)
        )

        ScriptGenerationClassLoader(
            emptyArray(),
            forwardingParent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            assertSame(defining.loadClass(className), loader.loadClass(className))
            assertEquals(setOf("AdventureExtension"), loader.pluginDependencies.snapshot())
        }

        ScriptGenerationClassLoader(
            emptyArray(),
            forwardingParent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            loader.invalidatePlugin("AdventureExtension")
            assertFailsWith<DisabledScriptPluginClassException> {
                loader.loadClass(className)
            }
        }
    }

    @Test
    fun `parent first class defined by embedded plugin loader is tracked`() {
        val className = PluginOnlyApi::class.java.name
        val testParent = DenyingClassLoader(className, javaClass.classLoader)
        val defining = IsolatedClassLoader(
            className,
            classBytes(PluginOnlyApi::class.java),
            testParent
        )
        val forwardingParent = PassthroughClassLoader(defining)
        val pluginLoader = EmbeddedDelegateClassLoader(defining, testParent)
        val snapshot = snapshot(
            forwardingParent,
            plugin("EmbeddedApi", pluginLoader)
        )

        ScriptGenerationClassLoader(
            emptyArray(),
            forwardingParent,
            snapshot,
            emptySet(),
            emptySet()
        ).use { loader ->
            assertSame(defining.loadClass(className), loader.loadClass(className))
            assertEquals(setOf("EmbeddedApi"), loader.pluginDependencies.snapshot())
        }
    }

    @Test
    fun `embedded defining loader is rejected after its plugin is invalidated`() {
        val className = PluginOnlyApi::class.java.name
        val testParent = DenyingClassLoader(className, javaClass.classLoader)
        val defining = IsolatedClassLoader(
            className,
            classBytes(PluginOnlyApi::class.java),
            testParent
        )
        val forwardingParent = PassthroughClassLoader(defining)
        val pluginLoader = EmbeddedDelegateClassLoader(defining, testParent)

        ScriptGenerationClassLoader(
            emptyArray(),
            forwardingParent,
            snapshot(forwardingParent, plugin("EmbeddedApi", pluginLoader)),
            emptySet(),
            emptySet()
        ).use { loader ->
            loader.invalidatePlugin("EmbeddedApi")

            assertFailsWith<DisabledScriptPluginClassException> {
                loader.loadClass(className)
            }
        }
    }

    @Test
    fun `parent first embedded identity conflict is rejected`() {
        val className = PluginOnlyApi::class.java.name
        val parent = PluginOnlyApi::class.java.classLoader
        val defining = IsolatedClassLoader(
            className,
            classBytes(PluginOnlyApi::class.java),
            parent
        )
        val pluginLoader = EmbeddedDelegateClassLoader(defining, parent)

        ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot(parent, plugin("EmbeddedApi", pluginLoader)),
            emptySet(),
            emptySet()
        ).use { loader ->
            val failure = assertFailsWith<ScriptClassIdentityConflictException> {
                loader.loadClass(className)
            }

            assertTrue(failure.message.orEmpty().contains("EmbeddedApi"))
            assertTrue(failure.message.orEmpty().contains("<runtime-parent>"))
        }
    }

    @Test
    fun `analysis tracks parent first class delegated from plugin`() {
        val jar = testJar(ParentFirstGeneratedReference::class.java)
        try {
            val className = PluginOnlyApi::class.java.name
            val testParent = DenyingClassLoader(
                setOf(className, ParentFirstGeneratedReference::class.java.name),
                javaClass.classLoader
            )
            val defining = IsolatedClassLoader(
                className,
                classBytes(PluginOnlyApi::class.java),
                testParent
            )
            val forwardingParent = PassthroughClassLoader(defining)
            val analysis = ScriptClassReferenceAnalyzer.analyze(
                jar,
                snapshot(
                    forwardingParent,
                    plugin("AdventureExtension", defining)
                )
            )

            assertEquals(setOf("AdventureExtension"), analysis.pluginOwnerNames)
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `plugin resources are delegated without reopening plugin jars`() {
        val resourceName = "META-INF/services/example.PluginService"
        val resourceRoot = Path.of("build", "tmp", "script-classloader-tests")
        Files.createDirectories(resourceRoot)
        val resourceFile = Files.createTempFile(
            resourceRoot,
            "plugin-service-",
            ".txt"
        )
        try {
            val resourceUrl = resourceFile.toUri().toURL()
            val parent = javaClass.classLoader
            val provider = ResourceClassLoader(
                parent,
                mapOf(resourceName to resourceUrl)
            )
            ScriptGenerationClassLoader(
                emptyArray(),
                parent,
                snapshot(parent, plugin("ServicePlugin", provider)),
                emptySet(),
                emptySet()
            ).use { loader ->
                assertEquals(resourceUrl, loader.getResource(resourceName))
                assertEquals(setOf("ServicePlugin"), loader.pluginDependencies.snapshot())
                assertEquals(
                    listOf(resourceUrl),
                    loader.getResources(resourceName).toList()
                )
                loader.invalidatePlugin("ServicePlugin")
                assertEquals(null, loader.getResource(resourceName))
            }
        } finally {
            Files.deleteIfExists(resourceFile)
        }
    }

    @Test
    fun `resources inherited from the parent do not create plugin dependencies`() {
        val resourceName = "META-INF/services/example.ParentService"
        val resourceRoot = Path.of("build", "tmp", "script-classloader-tests")
        Files.createDirectories(resourceRoot)
        val resourceFile = Files.createTempFile(
            resourceRoot,
            "parent-service-",
            ".txt"
        )
        try {
            val resourceUrl = resourceFile.toUri().toURL()
            val parent = ResourceClassLoader(
                javaClass.classLoader,
                mapOf(resourceName to resourceUrl)
            )
            val snapshot = snapshot(
                parent,
                plugin("Alpha", PassthroughClassLoader(parent)),
                plugin("Beta", PassthroughClassLoader(parent))
            )

            ScriptGenerationClassLoader(
                emptyArray(),
                parent,
                snapshot,
                emptySet(),
                emptySet()
            ).use { loader ->
                assertEquals(
                    listOf(resourceUrl),
                    loader.getResources(resourceName).toList()
                )
                assertTrue(loader.pluginDependencies.snapshot().isEmpty())
            }
        } finally {
            Files.deleteIfExists(resourceFile)
        }
    }

    @Test
    fun `invalidating an unused plugin does not retain its class loader`() {
        val parent = DuplicateApi::class.java.classLoader
        val (loader, weakLoader) = invalidatedEphemeralGeneration(parent)
        loader.use {

            repeat(40) {
                if (weakLoader.get() == null) return@repeat
                System.gc()
                System.runFinalization()
                Thread.sleep(10)
            }

            assertNull(weakLoader.get())
        }
    }

    @Test
    fun `generated class cannot shadow a runtime parent class`() {
        val jar = testJar(DuplicateApi::class.java)
        try {
            val parent = DuplicateApi::class.java.classLoader

            val failure = assertFailsWith<ScriptClassIdentityConflictException> {
                ScriptClassReferenceAnalyzer.analyze(jar, snapshot(parent))
            }

            assertTrue(failure.message.orEmpty().contains(DuplicateApi::class.java.name))
            assertTrue(failure.message.orEmpty().contains("<generated-project>"))
            assertTrue(failure.message.orEmpty().contains("<runtime-parent>"))
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `generated class cannot shadow a plugin class`() {
        val jar = testJar(PluginOnlyApi::class.java)
        try {
            val className = PluginOnlyApi::class.java.name
            val parent = DenyingClassLoader(className, javaClass.classLoader)
            val defining = IsolatedClassLoader(
                className,
                classBytes(PluginOnlyApi::class.java),
                parent
            )

            val failure = assertFailsWith<ScriptClassIdentityConflictException> {
                ScriptClassReferenceAnalyzer.analyze(
                    jar,
                    snapshot(parent, plugin("PluginApi", defining))
                )
            }

            assertTrue(failure.message.orEmpty().contains(className))
            assertTrue(failure.message.orEmpty().contains("<generated-project>"))
            assertTrue(failure.message.orEmpty().contains("PluginApi"))
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `generated jar references are rejected before evaluation on identity conflict`() {
        val jar = testJar(GeneratedReference::class.java)
        try {
            val bytes = classBytes(DuplicateApi::class.java)
            val parent = DenyingClassLoader(
                GeneratedReference::class.java.name,
                DuplicateApi::class.java.classLoader
            )
            val snapshot = snapshot(
                parent,
                plugin("Alpha", IsolatedClassLoader(DuplicateApi::class.java.name, bytes, parent)),
                plugin("Beta", IsolatedClassLoader(DuplicateApi::class.java.name, bytes, parent))
            )

            val failure = assertFailsWith<ScriptClassIdentityConflictException> {
                ScriptClassReferenceAnalyzer.analyze(jar, snapshot)
            }

            assertTrue(failure.message.orEmpty().contains(DuplicateApi::class.java.name))
            assertEquals(1, snapshot.conflicts.size)
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `generated jar analysis records only the defining plugin owner`() {
        val jar = testJar(GeneratedReference::class.java)
        try {
            val bytes = classBytes(DuplicateApi::class.java)
            val parent = DenyingClassLoader(
                setOf(
                    DuplicateApi::class.java.name,
                    GeneratedReference::class.java.name
                ),
                DuplicateApi::class.java.classLoader
            )
            val defining = IsolatedClassLoader(DuplicateApi::class.java.name, bytes, parent)
            val snapshot = snapshot(
                parent,
                plugin("Alpha", defining),
                plugin("Beta", PassthroughClassLoader(defining))
            )

            val analysis = ScriptClassReferenceAnalyzer.analyze(jar, snapshot)

            assertTrue(GeneratedReference::class.java.name in analysis.declaredClassNames)
            assertTrue(DuplicateApi::class.java.name in analysis.referencedClassNames)
            assertEquals(setOf("Alpha"), analysis.pluginOwnerNames)
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    private fun snapshot(
        parent: ClassLoader,
        vararg plugins: ScriptPluginClasspathPlugin
    ) = ScriptPluginClasspathSnapshot(
        parentClassLoader = parent,
        coreFiles = emptyList(),
        libraryFiles = emptyList(),
        plugins = plugins.toList(),
        files = emptyList(),
        fingerprint = "test"
    )

    private fun plugin(
        name: String,
        classLoader: ClassLoader
    ) = ScriptPluginClasspathPlugin(
        name = name,
        version = "1.0.0",
        files = emptyList(),
        classLoader = classLoader,
        ownedClassLoaders = classLoader.ownedClassLoaders()
    )

    private fun invalidatedEphemeralGeneration(
        parent: ClassLoader
    ): Pair<ScriptGenerationClassLoader, WeakReference<ClassLoader>> {
        val pluginLoader = PassthroughClassLoader(parent)
        val weak = WeakReference<ClassLoader>(pluginLoader)
        val generationLoader = ScriptGenerationClassLoader(
            emptyArray(),
            parent,
            snapshot(parent, plugin("Ephemeral", pluginLoader)),
            emptySet(),
            emptySet()
        )
        generationLoader.invalidatePlugin("Ephemeral")
        return generationLoader to weak
    }

    private fun classBytes(type: Class<*>): ByteArray {
        val resourceName = "/${type.name.replace('.', '/')}.class"
        return checkNotNull(type.getResourceAsStream(resourceName)) {
            "Missing test class resource $resourceName"
        }.use { input -> input.readAllBytes() }
    }

    private fun testJar(vararg types: Class<*>): Path {
        val directory = Path.of("build", "tmp", "script-classloader-tests")
        Files.createDirectories(directory)
        val jar = Files.createTempFile(directory, "generation-", ".jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            types.forEach { type ->
                output.putNextEntry(JarEntry("${type.name.replace('.', '/')}.class"))
                output.write(classBytes(type))
                output.closeEntry()
            }
        }
        return jar
    }

    private class IsolatedClassLoader(
        private val targetName: String,
        private val targetBytes: ByteArray,
        parent: ClassLoader
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            synchronized(getClassLoadingLock(name)) {
                if (name != targetName) {
                    return@synchronized super.loadClass(name, resolve)
                }
                val loaded = findLoadedClass(name) ?: defineClass(
                    name,
                    targetBytes,
                    0,
                    targetBytes.size
                )
                if (resolve) resolveClass(loaded)
                loaded
            }
    }

    private class PassthroughClassLoader(
        parent: ClassLoader
    ) : ClassLoader(parent)

    private class EmbeddedDelegateClassLoader(
        @Suppress("unused")
        private val libraryLoader: ClassLoader,
        parent: ClassLoader
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            try {
                libraryLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
    }

    private class DenyingClassLoader(
        private val deniedNames: Set<String>,
        parent: ClassLoader
    ) : ClassLoader(parent) {
        constructor(deniedName: String, parent: ClassLoader) : this(
            setOf(deniedName),
            parent
        )

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name in deniedNames) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

    private class ResourceClassLoader(
        parent: ClassLoader,
        private val resources: Map<String, URL>
    ) : ClassLoader(parent) {
        override fun getResource(name: String): URL? =
            resources[name] ?: super.getResource(name)

        override fun getResources(name: String): java.util.Enumeration<URL> =
            resources[name]?.let { Collections.enumeration(listOf(it)) }
                ?: super.getResources(name)
    }
}
