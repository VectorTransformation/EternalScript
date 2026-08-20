package eternalscript.scripting.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScriptPathTest {
    @Test
    fun `accepts normalized logical EternalScript paths`() {
        val result = assertIs<ScriptPathResult.Valid>(validateScriptPath("combat/a.eternal.kts"))
        assertEquals("combat/a.eternal.kts", result.path)
    }

    @Test
    fun `rejects traversal absolute backslash and wrong extensions`() {
        listOf(
            "../a.eternal.kts",
            "combat/../a.eternal.kts",
            "/a.eternal.kts",
            "C:/a.eternal.kts",
            "combat\\a.eternal.kts",
            "a.kts",
            "a.kt"
        ).forEach { path ->
            assertIs<ScriptPathResult.Invalid>(validateScriptPath(path), path)
        }
    }

    @Test
    fun `accepts file and directory targets and removes one disabled prefix`() {
        assertEquals(
            "combat/a.eternal.kts",
            assertIs<ScriptPathResult.Valid>(validateScriptTargetPath("combat/-a.eternal.kts")).path
        )
        assertEquals(
            "combat/bosses",
            assertIs<ScriptPathResult.Valid>(validateScriptTargetPath("combat/-bosses")).path
        )
        assertEquals(
            "combat/bosses",
            assertIs<ScriptPathResult.Valid>(validateScriptTargetPath("combat/bosses")).path
        )
    }

    @Test
    fun `rejects targeting a child through a disabled parent`() {
        assertIs<ScriptPathResult.Invalid>(validateScriptTargetPath("-combat/a.eternal.kts"))
    }

    @Test
    fun `reserves exactly one leading dash for disabled targets`() {
        listOf(
            "--combat",
            "combat/--a.eternal.kts",
            "-"
        ).forEach { path ->
            assertIs<ScriptPathResult.Invalid>(validateScriptTargetPath(path), path)
        }
    }

    @Test
    fun `rejects unsafe target paths`() {
        listOf(
            "../combat",
            "combat/../bosses",
            "/combat",
            "C:/combat",
            "combat\\bosses"
        ).forEach { path ->
            assertIs<ScriptPathResult.Invalid>(validateScriptTargetPath(path), path)
        }
    }
}
