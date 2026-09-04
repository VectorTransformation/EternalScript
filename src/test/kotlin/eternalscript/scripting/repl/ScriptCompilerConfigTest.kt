package eternalscript.scripting.repl

import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptCompilerConfigTest {
    @Test
    fun `script target matches the build Java runtime`() {
        assertEquals(Runtime.version().feature().toString(), SCRIPT_JVM_TARGET)
    }
}
