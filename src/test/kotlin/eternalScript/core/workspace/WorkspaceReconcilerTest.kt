package eternalScript.core.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceReconcilerTest {
    @Test
    fun `first generation is complete repeatable and preserves server data`() = withWorkspace {
        val scripts = resolve("scripts").createDirectories()
        val script = scripts.resolve("hello.kt").createFile()
        script.writeText("val hello = \"server\"\n")
        val library = resolve("libs").createDirectories().resolve("api.jar").createFile()
        val classpath = resolve("서버 플러그인 one.jar").createFile()
        val reconciler = WorkspaceReconciler(this, catalog("1", "build-v1"))

        val first = reconciler.reconcile(listOf(classpath, classpath), activePluginCount = 4)

        assertTrue(first.successful, first.errors.joinToString())
        assertEquals("build-v1", resolve("build.gradle.kts").readText())
        assertEquals("val hello = \"server\"\n", script.readText())
        assertTrue(library.exists())
        assertEquals(
            "${classpath.toAbsolutePath().normalize()}\n",
            resolve(".eternalscript/runtime-classpath.txt").readText()
        )
        assertEquals(4, first.status.activePluginCount)
        assertEquals(1, first.status.classpathEntryCount)
        assertEquals(0, first.status.conflictCount)
        assertEquals(WorkspaceState.READY, first.status.state)
        assertTrue(first.ideRefreshRecommended)
        assertContains(first.createdFiles, "build.gradle.kts")
        assertContains(first.createdFiles, "workspace.local.gradle.kts")
        assertContains(first.createdFiles, ".eternalscript/manifest.json")

        val manifest = resolve(".eternalscript/manifest.json").readText()
        assertContains(manifest, "\"schemaVersion\": 1")
        assertContains(manifest, "\"templateVersion\": \"1\"")
        assertContains(manifest, sha256("build-v1".toByteArray()))

        val second = reconciler.reconcile(listOf(classpath), activePluginCount = 4)

        assertTrue(second.successful)
        assertTrue(second.createdFiles.isEmpty())
        assertTrue(second.updatedFiles.isEmpty())
        assertTrue(second.conflictFiles.isEmpty())
        assertEquals(WorkspaceState.READY, second.status.state)
        assertFalse(second.ideRefreshRecommended)
        assertTrue(
            Files.walk(this).use { paths ->
                paths.noneMatch { path -> path.fileName.toString().endsWith(".tmp") }
            }
        )
    }

    @Test
    fun `unmodified managed file upgrades in place`() = withWorkspace {
        WorkspaceReconciler(this, catalog("1", "generated-v1"))
            .reconcile(emptyList(), activePluginCount = 1)

        val upgraded = WorkspaceReconciler(this, catalog("2", "generated-v2"))
            .reconcile(emptyList(), activePluginCount = 1)

        assertTrue(upgraded.successful)
        assertEquals("generated-v2", resolve("build.gradle.kts").readText())
        assertContains(upgraded.updatedFiles, "build.gradle.kts")
        assertTrue(upgraded.conflictFiles.isEmpty())
        assertEquals(0, upgraded.status.conflictCount)
        assertEquals(WorkspaceState.READY, upgraded.status.state)
        assertTrue(upgraded.ideRefreshRecommended)
    }

    @Test
    fun `user edit is preserved and new template is written as a conflict`() = withWorkspace {
        val first = WorkspaceReconciler(this, catalog("1", "generated-v1"))
        first.reconcile(emptyList(), activePluginCount = 1)
        resolve("build.gradle.kts").writeText("owner-build")
        resolve("workspace.local.gradle.kts").writeText("owner-local")

        val upgraded = WorkspaceReconciler(this, catalog("2", "generated-v2"))
            .reconcile(emptyList(), activePluginCount = 1)

        assertTrue(upgraded.successful)
        assertEquals("owner-build", resolve("build.gradle.kts").readText())
        assertEquals("owner-local", resolve("workspace.local.gradle.kts").readText())
        assertEquals(
            "generated-v2",
            resolve(".eternalscript/conflicts/1/build.gradle.kts").readText()
        )
        assertEquals(
            listOf(".eternalscript/conflicts/1/build.gradle.kts"),
            upgraded.conflictFiles
        )
        assertEquals(1, upgraded.status.conflictCount)
        assertEquals(WorkspaceState.ACTION_REQUIRED, upgraded.status.state)

        val repeated = WorkspaceReconciler(this, catalog("2", "generated-v2"))
            .reconcile(emptyList(), activePluginCount = 1)
        assertTrue(repeated.createdFiles.isEmpty())
        assertTrue(repeated.updatedFiles.isEmpty())
        assertEquals("owner-build", resolve("build.gradle.kts").readText())
        assertEquals(1, repeated.status.conflictCount)
    }

    @Test
    fun `preexisting file without a manifest is never adopted as generated`() = withWorkspace {
        resolve("build.gradle.kts").writeText("preexisting")

        val result = WorkspaceReconciler(this, catalog("1", "candidate"))
            .reconcile(emptyList(), activePluginCount = 0)

        assertTrue(result.successful)
        assertEquals("preexisting", resolve("build.gradle.kts").readText())
        assertEquals(
            "candidate",
            resolve(".eternalscript/conflicts/1/build.gradle.kts").readText()
        )
        assertContains(
            resolve(".eternalscript/manifest.json").readText(),
            "\"appliedSha256\": null"
        )
    }

    @Test
    fun `corrupt manifest fails safely without overwriting an older generated file`() = withWorkspace {
        WorkspaceReconciler(this, catalog("1", "generated-v1"))
            .reconcile(emptyList(), activePluginCount = 0)
        resolve(".eternalscript/manifest.json").writeText("{not-json")

        val result = WorkspaceReconciler(this, catalog("2", "generated-v2"))
            .reconcile(emptyList(), activePluginCount = 0)

        assertFalse(result.successful)
        assertNotNull(result.status.lastError)
        assertEquals(WorkspaceState.ERROR, result.status.state)
        assertEquals("generated-v1", resolve("build.gradle.kts").readText())
        assertEquals(
            "generated-v2",
            resolve(".eternalscript/conflicts/1/build.gradle.kts").readText()
        )
    }

    @Test
    fun `a missing managed file is recreated from the current template`() = withWorkspace {
        WorkspaceReconciler(this, catalog("1", "generated-v1"))
            .reconcile(emptyList(), activePluginCount = 0)
        Files.delete(resolve("build.gradle.kts"))

        val result = WorkspaceReconciler(this, catalog("2", "generated-v2"))
            .reconcile(emptyList(), activePluginCount = 0)

        assertTrue(result.successful)
        assertEquals("generated-v2", resolve("build.gradle.kts").readText())
        assertContains(result.createdFiles, "build.gradle.kts")
    }

    @Test
    fun `status detects a user edit made after generation`() = withWorkspace {
        val reconciler = WorkspaceReconciler(this, catalog("1", "generated"))
        reconciler.reconcile(emptyList(), activePluginCount = 2)
        resolve("build.gradle.kts").writeText("owner-edit")

        val status = reconciler.inspect(
            classpathEntries = emptyList(),
            activePluginCount = 2
        )

        assertEquals(1, status.conflictCount)
        assertEquals(WorkspaceState.ACTION_REQUIRED, status.state)
        assertNull(status.lastError)
    }

    @Test
    fun `symbolic directory traversal is rejected without writing outside workspace`() = withWorkspace {
        val outside = Files.createTempDirectory(parent, "workspace-symlink-target-")
        try {
            val linkCreated = runCatching {
                Files.createSymbolicLink(resolve("gradle"), outside)
                true
            }.getOrDefault(false)
            if (!linkCreated) return@withWorkspace

            val result = WorkspaceReconciler(this, catalog("1", "generated"))
                .reconcile(emptyList(), activePluginCount = 0)

            assertFalse(result.successful)
            assertFalse(outside.resolve("wrapper").exists())
            assertTrue(result.errors.any { it.contains("symbolic link", ignoreCase = true) })
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `packaged Gradle workspace uses verified versions and runtime filtering`() {
        val catalog = DefaultWorkspaceTemplates.load()
        val wrapper = catalog.managed.single {
            it.target == "gradle/wrapper/gradle-wrapper.jar"
        }
        val properties = catalog.managed.single {
            it.target == "gradle/wrapper/gradle-wrapper.properties"
        }.content.toString(StandardCharsets.UTF_8)
        val build = catalog.managed.single {
            it.target == "build.gradle.kts"
        }.content.toString(StandardCharsets.UTF_8)
        val workspaceGuide = catalog.managed.single {
            it.target == "WORKSPACE.md"
        }.content.toString(StandardCharsets.UTF_8)
        val unixLauncher = catalog.managed.single {
            it.target == "gradlew"
        }

        assertEquals(DefaultWorkspaceTemplates.WRAPPER_JAR_SHA256, sha256(wrapper.content))
        assertContains(properties, "gradle-${DefaultWorkspaceTemplates.GRADLE_VERSION}-bin.zip")
        assertContains(
            properties,
            "distributionSha256Sum=${DefaultWorkspaceTemplates.GRADLE_DISTRIBUTION_SHA256}"
        )
        assertContains(build, "kotlin(\"jvm\") version \"${DefaultWorkspaceTemplates.KOTLIN_VERSION}\"")
        assertContains(build, "jvmToolchain(${DefaultWorkspaceTemplates.JAVA_VERSION})")
        assertContains(build, "kotlin.include(\"**/*.kt\")")
        assertContains(build, "part.startsWith(\"-\")")
        assertContains(build, "ScriptProjectCheckTool")
        assertContains(build, "without server classloader checks")
        assertContains(build, "dependsOn(checkScripts)")
        assertContains(workspaceGuide, "compile-only")
        assertContains(workspaceGuide, "Run `/es check`")
        assertTrue(unixLauncher.executable)
        assertTrue(unixLauncher.content.toString(StandardCharsets.UTF_8).startsWith("#!/bin/sh"))
    }

    private fun catalog(
        version: String,
        build: String
    ) = WorkspaceTemplateCatalog(
        templateVersion = version,
        managed = listOf(
            WorkspaceTemplate(
                target = "build.gradle.kts",
                content = build.toByteArray(StandardCharsets.UTF_8)
            ),
            WorkspaceTemplate(
                target = "gradle/wrapper/gradle-wrapper.properties",
                content = "distribution-$version".toByteArray(StandardCharsets.UTF_8)
            )
        ),
        localOverride = WorkspaceTemplate(
            target = "workspace.local.gradle.kts",
            content = "owner settings\n".toByteArray(StandardCharsets.UTF_8)
        )
    )

    private fun <T> withWorkspace(block: Path.() -> T): T {
        val parent = Path.of("codex", "temp", "workspace-generator-tests")
            .toAbsolutePath()
            .normalize()
            .createDirectories()
        val workspace = Files.createTempDirectory(parent, "한글 workspace ")
        return try {
            workspace.block()
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun sha256(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
