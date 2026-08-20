package eternalscript.scripting.runtime

import eternalscript.api.script.Script
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScriptLifecycleHooksTest {
    @Test
    fun `onLoad stops at the first failure`() {
        val calls = mutableListOf<String>()
        val hooks = ScriptLifecycleHooks(
            loadCallbacks = listOf(
                {
                    calls += "first"
                    error("load failed")
                },
                { calls += "second" }
            ),
            unloadCallbacks = emptyList()
        )

        assertFailsWith<IllegalStateException> { hooks.invokeLoad() }
        assertEquals(listOf("first"), calls)
    }

    @Test
    fun `onUnload runs every callback and combines failures`() {
        val calls = mutableListOf<String>()
        val hooks = ScriptLifecycleHooks(
            loadCallbacks = emptyList(),
            unloadCallbacks = listOf(
                {
                    calls += "first"
                    error("first failure")
                },
                { calls += "second" },
                {
                    calls += "third"
                    throw UnsupportedOperationException("third failure")
                }
            )
        )

        val failure = assertFailsWith<IllegalStateException> { hooks.invokeUnload() }
        assertEquals(listOf("first", "second", "third"), calls)
        assertEquals("first failure", failure.message)
        assertEquals("third failure", failure.suppressed.single().message)
    }

    @Test
    fun `script exposes only lifecycle event and command DSL`() {
        val methods = Script::class.java.declaredMethods.map { method -> method.name }.toSet()

        assertTrue("onLoad" in methods)
        assertTrue("onUnload" in methods)
        assertTrue("onDispose" in methods)
        assertTrue("own" in methods)
        assertTrue("on" in methods)
        assertTrue("command" in methods)
        assertFalse("instance" in methods)
        assertFalse("scripts" in methods)
        assertFalse("script" in methods)
        assertFalse("call" in methods)
        assertFalse("export" in methods)
    }

    @Test
    fun `declarations freeze after evaluation staging`() {
        val script = object : Script() {}
        script.onLoad {}
        val snapshot = script.freezeDeclarations()

        assertEquals(1, snapshot.loadCallbacks.size)
        assertFailsWith<IllegalStateException> { script.onUnload {} }
    }

    @Test
    fun `owned resources dispose once in reverse order and combine failures`() {
        val calls = mutableListOf<String>()
        val script = object : Script() {}
        script.onDispose {
            calls += "first"
            error("first failure")
        }
        val closeable = AutoCloseable { calls += "second" }
        assertSame(closeable, script.own(closeable))
        script.onDispose {
            calls += "third"
            throw UnsupportedOperationException("third failure")
        }
        script.freezeDeclarations()

        val failure = assertFailsWith<UnsupportedOperationException> { script.disposeDeclarations() }

        assertEquals(listOf("third", "second", "first"), calls)
        assertEquals("third failure", failure.message)
        assertEquals("first failure", failure.suppressed.single().message)

        script.disposeDeclarations()
        assertEquals(listOf("third", "second", "first"), calls)
    }

    @Test
    fun `resources can be owned during onLoad after declarations freeze`() {
        val calls = mutableListOf<String>()
        val script = object : Script() {}
        script.onLoad {
            script.onDispose { calls += "callback" }
            script.own("resource") { resource -> calls += resource }
        }
        val snapshot = script.freezeDeclarations()

        snapshot.loadCallbacks.single().invoke()
        script.disposeDeclarations()

        assertEquals(listOf("resource", "callback"), calls)
    }

    @Test
    fun `own immediately cleans a resource rejected after disposal`() {
        val script = object : Script() {}
        var closed = false
        script.disposeDeclarations()

        assertFailsWith<IllegalStateException> {
            script.own(AutoCloseable { closed = true })
        }

        assertTrue(closed)
    }
}
