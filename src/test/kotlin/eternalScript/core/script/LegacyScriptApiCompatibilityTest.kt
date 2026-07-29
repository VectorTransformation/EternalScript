package eternalScript.core.script

import eternalScript.core.manager.ScriptManager
import eternalScript.core.script.data.ScriptData
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyScriptApiCompatibilityTest {
    @Test
    fun `deprecated script lookup JVM descriptors remain available`() {
        val parameterTypes = arrayOf(String::class.java)

        assertEquals(
            ScriptData::class.java,
            ScriptManager::class.java
                .getDeclaredMethod("script", *parameterTypes)
                .returnType
        )
        assertEquals(
            ScriptData::class.java,
            Script::class.java
                .getDeclaredMethod("script", *parameterTypes)
                .returnType
        )
    }
}
