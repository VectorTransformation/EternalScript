package eternalScript.core.script.project

import eternalScript.core.script.data.ScriptSuffix
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptProjectRepositoryTest {
    @Test
    fun `script suffix accepts only Kotlin source files`() {
        assertTrue(ScriptSuffix.SCRIPT.check(File("active.kt")))
        assertFalse(ScriptSuffix.SCRIPT.check(File("active.KT")))
        assertFalse(ScriptSuffix.SCRIPT.check(File("legacy.kts")))
        assertFalse(ScriptSuffix.SCRIPT.check(File("legacy.eternal.kts")))
    }

    @Test
    fun `stable sources compose in deterministic path order`() {
        val repository = ScriptProjectRepository(discover = {
            listOf(
                source("nested/b.kt", "fun b() = a()"),
                source("a.kt", "fun a() = 1"),
                legacy("old.kts"),
                legacy("-shared/helper.eternal.kts")
            )
        })

        val project = requireNotNull(repository.snapshot())

        assertEquals(listOf("a.kt", "nested/b.kt"), repository.paths())
        assertEquals(listOf("-shared/helper.eternal.kts", "old.kts"), repository.legacyPaths())
        assertEquals(listOf("a.kt", "nested/b.kt"), project.files.map { it.name })
    }

    @Test
    fun `legacy extension detection is case insensitive and excludes project scripts`() {
        assertFalse(isLegacyScriptPath("old.kt"))
        assertTrue(isLegacyScriptPath("old.KTS"))
        assertTrue(isLegacyScriptPath("active.eternal.kts"))
        assertTrue(isLegacyScriptPath("nested/active.ETERNAL.KTS"))
        assertFalse(isLegacyScriptPath("-shared/helper.Kt"))
        assertFalse(isLegacyScriptPath("notes.txt"))
    }

    @Test
    fun `runtime source paths exclude every dash-prefixed segment`() {
        assertTrue(isRuntimeScriptPath("active.kt"))
        assertFalse(isRuntimeScriptPath("nested/active.KT"))
        assertFalse(isRuntimeScriptPath("-draft.kt"))
        assertFalse(isRuntimeScriptPath("-examples/demo.kt"))
        assertFalse(isRuntimeScriptPath("nested/-draft/demo.kt"))
        assertFalse(isRuntimeScriptPath("legacy.kts"))
        assertFalse(isRuntimeScriptPath("legacy.eternal.kts"))
    }

    @Test
    fun `legacy warning reports total and bounded preview`() {
        val paths = (1..7).map { "legacy-$it.kts" }

        assertEquals(
            "Ignored 7 legacy .kts/.eternal.kts script source(s): " +
                "legacy-1.kts, legacy-2.kts, legacy-3.kts, legacy-4.kts, legacy-5.kts " +
                "and 2 more. Project mode only loads *.kt; " +
                "migrate these files before reloading.",
            legacyScriptWarning(paths)
        )
        assertNull(legacyScriptWarning(emptyList()))
    }

    @Test
    fun `empty project is confirmed before returning null`() {
        var discoveries = 0
        val repository = ScriptProjectRepository(discover = {
            discoveries += 1
            emptyList<ScriptProjectEntry>()
        })

        assertNull(repository.snapshot())
        assertEquals(2, discoveries)
    }

    @Test
    fun `snapshot retries a transient content change`() {
        var discoveries = 0
        val repository = ScriptProjectRepository(snapshotAttempts = 2, discover = {
            discoveries += 1
            val text = if (discoveries == 1) "val version = 1" else "val version = 2"
            listOf(source("state.kt", text))
        })

        val project = requireNotNull(repository.snapshot())

        assertEquals("val version = 2", project.files.single().text)
        assertEquals(4, discoveries)
    }

    @Test
    fun `unstable project fails after bounded retries`() {
        var version = 0
        val repository = ScriptProjectRepository(snapshotAttempts = 2, discover = {
            version += 1
            listOf(source("state.kt", "val version = $version"))
        })

        assertFailsWith<IllegalStateException> {
            repository.snapshot()
        }
        assertEquals(4, version)
    }

    private fun source(path: String, text: String) =
        ScriptProjectEntry(path, ScriptProjectEntryKind.SOURCE) { text }

    private fun legacy(path: String) =
        ScriptProjectEntry(path, ScriptProjectEntryKind.LEGACY) { "" }
}
