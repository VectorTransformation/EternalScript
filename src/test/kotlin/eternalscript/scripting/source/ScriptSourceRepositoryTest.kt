package eternalscript.scripting.source

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptSourceRepositoryTest {
    @Test
    fun `scans only normalized enabled eternal scripts`() {
        val root = Files.createTempDirectory("eternalscript-sources")
        val external = Files.createTempDirectory("eternalscript-external-sources")
        try {
            root.resolve("nested").createDirectories()
            root.resolve("-disabled").createDirectories()
            root.resolve("B.eternal.kts").writeText("val b = 2")
            root.resolve("nested/a.eternal.kts").writeText("val a = 1")
            root.resolve("-ignored.eternal.kts").writeText("error(\"ignored\")")
            root.resolve("-disabled/ignored.eternal.kts").writeText("error(\"ignored\")")
            root.resolve("legacy.kt").writeText("error(\"ignored\")")
            external.resolve("outside.eternal.kts").writeText("error(\"outside\")")
            runCatching { Files.createSymbolicLink(root.resolve("linked"), external) }

            val sources = readScriptSources(root.toFile())

            assertEquals(listOf("B.eternal.kts", "nested/a.eternal.kts"), sources.map(ScriptSourceFile::name))
            assertEquals(listOf("val b = 2", "val a = 1"), sources.map(ScriptSourceFile::text))
        } finally {
            Files.deleteIfExists(root.resolve("linked"))
            root.toFile().deleteRecursively()
            external.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects a scripts root that is not a directory`() {
        val root = Files.createTempFile("eternalscript-sources", ".tmp")
        try {
            assertFailsWith<IllegalStateException> { readScriptSources(root.toFile()) }
        } finally {
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun `unload and load rename one script and can roll back`() {
        val root = Files.createTempDirectory("eternalscript-file-target")
        try {
            root.resolve("a.eternal.kts").writeText("val a = 1")

            val unloaded = assertIs<ScriptTargetPreparation.Ready>(
                prepareScriptUnloadTarget(root.toFile(), "a.eternal.kts")
            ).transition
            assertTrue(unloaded.changed)
            assertTrue(Files.exists(root.resolve("a.eternal.kts")))
            assertFalse(Files.exists(root.resolve("-a.eternal.kts")))

            unloaded.apply()
            assertFalse(Files.exists(root.resolve("a.eternal.kts")))
            assertTrue(Files.exists(root.resolve("-a.eternal.kts")))
            assertEquals(emptyList(), readScriptSources(root.toFile()))

            unloaded.rollback()
            assertTrue(Files.exists(root.resolve("a.eternal.kts")))
            assertFalse(Files.exists(root.resolve("-a.eternal.kts")))

            val disabled = assertIs<ScriptTargetPreparation.Ready>(
                prepareScriptUnloadTarget(root.toFile(), "a.eternal.kts")
            ).transition
            disabled.apply()
            disabled.commit()
            val loaded = assertIs<ScriptTargetPreparation.Ready>(
                prepareScriptLoadTarget(root.toFile(), "-a.eternal.kts")
            ).transition
            assertEquals("a.eternal.kts", loaded.target.path)
            assertEquals(ScriptTargetKind.FILE, loaded.target.kind)
            loaded.apply()
            loaded.commit()
            assertEquals(listOf("a.eternal.kts"), readScriptSources(root.toFile()).map(ScriptSourceFile::name))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `folder target renames only the folder and preserves nested disabled entries`() {
        val root = Files.createTempDirectory("eternalscript-folder-target")
        try {
            val folder = root.resolve("combat").createDirectories()
            folder.resolve("a.eternal.kts").writeText("val a = 1")
            folder.resolve("-ignored.eternal.kts").writeText("error(\"ignored\")")
            folder.resolve("-nested").createDirectories()
                .resolve("ignored.eternal.kts").writeText("error(\"ignored\")")

            val unloaded = assertIs<ScriptTargetPreparation.Ready>(
                prepareScriptUnloadTarget(root.toFile(), "combat")
            ).transition
            assertEquals(ScriptTargetKind.DIRECTORY, unloaded.target.kind)
            assertTrue(Files.exists(root.resolve("combat")))
            unloaded.apply()
            assertEquals("-combat", root.toFile().listFiles().single().toPath().name)
            assertTrue(Files.exists(root.resolve("-combat/a.eternal.kts")))
            assertTrue(Files.exists(root.resolve("-combat/-ignored.eternal.kts")))
            assertTrue(Files.exists(root.resolve("-combat/-nested/ignored.eternal.kts")))
            unloaded.commit()

            val loaded = assertIs<ScriptTargetPreparation.Ready>(
                prepareScriptLoadTarget(root.toFile(), "combat")
            ).transition
            loaded.apply()
            loaded.commit()
            assertEquals(
                listOf("combat/a.eternal.kts"),
                readScriptSources(root.toFile()).map(ScriptSourceFile::name)
            )
            assertTrue(Files.exists(root.resolve("combat/-ignored.eternal.kts")))
            assertTrue(Files.exists(root.resolve("combat/-nested/ignored.eternal.kts")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `target preparation rejects enabled and disabled name collisions`() {
        val root = Files.createTempDirectory("eternalscript-target-collision")
        try {
            root.resolve("a.eternal.kts").writeText("val a = 1")
            root.resolve("-a.eternal.kts").writeText("val a = 2")

            assertIs<ScriptTargetPreparation.Invalid>(
                prepareScriptUnloadTarget(root.toFile(), "a.eternal.kts")
            )
            assertIs<ScriptTargetPreparation.Invalid>(
                prepareScriptLoadTarget(root.toFile(), "a.eternal.kts")
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rolling back a planned transition leaves the target untouched`() {
        val root = Files.createTempDirectory("eternalscript-planned-target")
        try {
            root.resolve("provider.eternal.kts").writeText("val provider = 1")
            val transition = assertIs<ScriptTargetPreparation.Ready>(
                prepareScriptUnloadTarget(root.toFile(), "provider.eternal.kts")
            ).transition

            transition.rollback()

            assertTrue(Files.exists(root.resolve("provider.eternal.kts")))
            assertFalse(Files.exists(root.resolve("-provider.eternal.kts")))
            assertFailsWith<IllegalStateException> { transition.apply() }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `target discovery exposes canonical file and folder paths only`() {
        val root = Files.createTempDirectory("eternalscript-target-discovery")
        try {
            root.resolve("combat").createDirectories()
                .resolve("a.eternal.kts").writeText("val a = 1")
            root.resolve("-disabled").createDirectories()
                .resolve("hidden.eternal.kts").writeText("error(\"hidden\")")
            root.resolve("-file.eternal.kts").writeText("val file = 1")
            root.resolve("--invalid.eternal.kts").writeText("error(\"invalid\")")
            root.resolve("--invalid-folder").createDirectories()

            assertEquals(
                listOf("combat", "combat/a.eternal.kts", "disabled", "file.eternal.kts"),
                discoverScriptTargets(root.toFile())
            )
            assertEquals(
                listOf(
                    DiscoveredScriptTarget("combat", ScriptTargetKind.DIRECTORY, true),
                    DiscoveredScriptTarget("combat/a.eternal.kts", ScriptTargetKind.FILE, true),
                    DiscoveredScriptTarget("disabled", ScriptTargetKind.DIRECTORY, false),
                    DiscoveredScriptTarget("file.eternal.kts", ScriptTargetKind.FILE, false)
                ),
                discoverScriptTargetEntries(root.toFile())
            )

            root.resolve("created-later.eternal.kts").writeText("val createdLater = true")
            assertEquals(
                listOf(
                    "combat",
                    "combat/a.eternal.kts",
                    "created-later.eternal.kts",
                    "disabled",
                    "file.eternal.kts"
                ),
                discoverScriptTargets(root.toFile())
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `combined scan returns sources and targets from the same tree state`() {
        val root = Files.createTempDirectory("eternalscript-combined-scan")
        try {
            root.resolve("active").createDirectories()
                .resolve("a.eternal.kts").writeText("val a = 1")
            root.resolve("-disabled").createDirectories()
                .resolve("hidden.eternal.kts").writeText("error(\"hidden\")")
            root.resolve("-file.eternal.kts").writeText("val disabled = true")

            val scan = scanScriptSources(root.toFile())

            assertEquals(listOf("active/a.eternal.kts"), scan.sources.map(ScriptSourceFile::name))
            assertEquals(
                listOf(
                    DiscoveredScriptTarget("active", ScriptTargetKind.DIRECTORY, true),
                    DiscoveredScriptTarget("active/a.eternal.kts", ScriptTargetKind.FILE, true),
                    DiscoveredScriptTarget("disabled", ScriptTargetKind.DIRECTORY, false),
                    DiscoveredScriptTarget("file.eternal.kts", ScriptTargetKind.FILE, false)
                ),
                scan.targets
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `target preparation rejects wrong file types and symbolic links`() {
        val root = Files.createTempDirectory("eternalscript-invalid-target")
        val external = Files.createTempDirectory("eternalscript-invalid-target-external")
        try {
            root.resolve("notes.txt").writeText("not a script")
            assertIs<ScriptTargetPreparation.Invalid>(
                prepareScriptLoadTarget(root.toFile(), "notes.txt")
            )

            external.resolve("linked.eternal.kts").writeText("val linked = true")
            val linked = root.resolve("linked.eternal.kts")
            if (runCatching { Files.createSymbolicLink(linked, external.resolve("linked.eternal.kts")) }.isSuccess) {
                assertIs<ScriptTargetPreparation.Invalid>(
                    prepareScriptLoadTarget(root.toFile(), "linked.eternal.kts")
                )
            }
        } finally {
            Files.deleteIfExists(root.resolve("linked.eternal.kts"))
            root.toFile().deleteRecursively()
            external.toFile().deleteRecursively()
        }
    }
}
