package eternalScript.core.feedback

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocaleCatalogTest {
    @Test
    fun `obsolete bundled catalog is replaced instead of retaining removed keys`() {
        val installed = catalog(
            "_schema" to JsonPrimitive(3),
            "removed.key" to JsonPrimitive("legacy"),
            "current.key" to JsonPrimitive("old customization")
        )
        val bundled = catalog(
            "_schema" to JsonPrimitive(4),
            "current.key" to JsonPrimitive("current")
        )

        val resolved = LocaleCatalog.resolveInstalledCatalog(installed, bundled)

        assertEquals(bundled, resolved)
        assertFalse("removed.key" in resolved)
    }

    @Test
    fun `current or custom catalog keeps installed values`() {
        val installed = catalog(
            "_schema" to JsonPrimitive(4),
            "current.key" to JsonPrimitive("customized"),
            "custom.key" to JsonPrimitive("custom")
        )
        val bundled = catalog(
            "_schema" to JsonPrimitive(4),
            "current.key" to JsonPrimitive("bundled"),
            "new.key" to JsonPrimitive("new")
        )

        val resolved = LocaleCatalog.resolveInstalledCatalog(installed, bundled)

        assertEquals("customized", resolved.getValue("current.key").toString().trim('"'))
        assertEquals("new", resolved.getValue("new.key").toString().trim('"'))
        assertEquals("custom", resolved.getValue("custom.key").toString().trim('"'))
    }

    private fun catalog(vararg values: Pair<String, JsonPrimitive>) =
        JsonObject(linkedMapOf(*values))
}
