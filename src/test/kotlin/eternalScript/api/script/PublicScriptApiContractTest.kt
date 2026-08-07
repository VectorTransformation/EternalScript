package eternalScript.api.script

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PublicScriptApiContractTest {
    @Test
    fun `only the current public script package is loadable`() {
        assertNotNull(Class.forName("eternalScript.api.script.EternalScript"))
        assertNotNull(Class.forName("eternalScript.api.script.EternalScriptRuntimeBridge"))
        assertNotNull(Class.forName("eternalScript.api.script.EternalScriptRuntimeAccess"))
        assertNotNull(Class.forName("eternalScript.api.script.command.ScriptCommandBuilder"))

        assertFailsWith<ClassNotFoundException> {
            Class.forName("eternalScript.api.script.Script")
        }

        assertFailsWith<ClassNotFoundException> {
            Class.forName("eternalScript.core.script.EternalScript")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("eternalScript.core.script.Script")
        }
    }
}
