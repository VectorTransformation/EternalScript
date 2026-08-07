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
        assertNotNull(Class.forName("eternalScript.api.script.EternalScriptDsl"))
        assertNotNull(Class.forName("eternalScript.api.script.ScriptEvents"))
        assertNotNull(Class.forName("eternalScript.api.script.command.ScriptCommandBuilder"))
        assertNotNull(Class.forName("eternalScript.api.script.command.ScriptCommandContext"))
        assertNotNull(Class.forName("eternalScript.api.script.command.ScriptCommandDefinition"))
        assertNotNull(Class.forName("eternalScript.api.script.command.ScriptCommands"))
        assertNotNull(Class.forName("eternalScript.api.script.command.ScriptSuggestionContext"))

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
