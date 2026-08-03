package eternalScript.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConfigContractTest {
    @Test
    fun `generated user configuration has no legacy runtime controls`() {
        assertEquals(
            setOf("lang", "libs", "debug", "metrics"),
            Config.entries.map(Config::key).toSet()
        )
        assertFalse(Config.DEBUG.value as Boolean)
    }
}
