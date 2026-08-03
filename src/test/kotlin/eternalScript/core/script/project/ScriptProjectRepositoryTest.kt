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
        assertFalse(ScriptSuffix.SCRIPT.check(File("active.java")))
        assertFalse(ScriptSuffix.SCRIPT.check(File("notes.txt")))
    }

    @Test
    fun `stable sources compose in deterministic path order`() {
        val repository = ScriptProjectRepository(discover = {
            listOf(
                source("nested/b.kt", "fun b() = a()"),
                source("a.kt", "fun a() = 1")
            )
        })

        val project = requireNotNull(repository.snapshot())

        assertEquals(listOf("a.kt", "nested/b.kt"), repository.paths())
        assertEquals(listOf("a.kt", "nested/b.kt"), project.files.map { it.name })
    }

    @Test
    fun `runtime source paths exclude every dash-prefixed segment`() {
        assertTrue(isRuntimeScriptPath("active.kt"))
        assertFalse(isRuntimeScriptPath("nested/active.KT"))
        assertFalse(isRuntimeScriptPath("-draft.kt"))
        assertFalse(isRuntimeScriptPath("-examples/demo.kt"))
        assertFalse(isRuntimeScriptPath("nested/-draft/demo.kt"))
        assertFalse(isRuntimeScriptPath("active.java"))
        assertFalse(isRuntimeScriptPath("notes.txt"))
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
        ScriptProjectEntry(path) { text }
}
