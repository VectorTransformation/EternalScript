package eternalScript.core.manager

import eternalScript.core.feedback.LocaleCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageResourceContractTest {
    private val json = Json

    @Test
    fun `bundled languages expose the same current user contract`() {
        val resources = LocaleCatalog.bundledLanguages.associateWith(::load)
        val english = assertNotNull(resources["en_US"])
        val keys = english.keys

        resources.forEach { (language, content) ->
            assertEquals(4, content.getValue("_schema").jsonPrimitive.int, language)
            assertEquals(keys, content.keys, language)
            keys.filterNot { key -> key == "_schema" }.forEach { key ->
                assertEquals(
                    english.getValue(key).jsonPrimitive.content.countPlaceholders(),
                    content.getValue(key).jsonPrimitive.content.countPlaceholders(),
                    "$language:$key"
                )
            }
        }

        assertTrue("script.reload.failed_preserved" in keys)
        assertTrue("feedback.next.example" in keys)
        assertTrue("script.status.next.example" !in keys)
        assertTrue("workspace.state.action_required" in keys)
        assertTrue("script.operation.scope.reload_all" !in keys)
        assertTrue("script.operation.accepted" !in keys)
    }

    private fun load(language: String): JsonObject {
        val path = "/lang/$language.json"
        val content = assertNotNull(javaClass.getResourceAsStream(path), path)
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> reader.readText() }
        return json.decodeFromString(content)
    }

    private fun String.countPlaceholders(): Int =
        windowed(size = 2, step = 1).count { token -> token == "%s" }
}
