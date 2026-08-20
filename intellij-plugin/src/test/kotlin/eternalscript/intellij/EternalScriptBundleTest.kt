package eternalscript.intellij

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class EternalScriptBundleTest {
    @Test
    fun `all supported locales contain the same non-empty messages`() {
        val resources = listOf(
            "messages/EternalScriptBundle.properties",
            "messages/EternalScriptBundle_ko.properties",
            "messages/EternalScriptBundle_ja.properties",
            "messages/EternalScriptBundle_zh_CN.properties"
        )
        val bundles = resources.associateWith(::load)
        val expectedKeys = bundles.getValue(resources.first()).stringPropertyNames()

        assertEquals(
            setOf(
                "environment.missing",
                "environment.invalid",
                "environment.incompatible",
                "environment.untrusted",
                "environment.unsafeRoot",
                "environment.missingClasspath",
                "environment.incompatibleKotlin",
                "analysis.failed",
                "analysis.unstable",
                "diagnostic.duplicate.declaration",
                "diagnostic.conflicting.import",
                "rename.conflict.declaration",
                "rename.conflict.import",
                "action.reload",
                "action.openManifest",
                "action.openRoot",
                "action.copyDiagnostics",
                "action.noWorkspace",
                "action.diagnosticsCopied",
                "action.reloadRequested"
            ),
            expectedKeys
        )
        bundles.forEach { (resource, properties) ->
            assertEquals(expectedKeys, properties.stringPropertyNames(), resource)
            expectedKeys.forEach { key ->
                assertTrue(properties.getProperty(key).isNotBlank(), "$resource:$key")
            }
        }
    }

    private fun load(resource: String): Properties = Properties().apply {
        val stream = requireNotNull(EternalScriptBundleTest::class.java.classLoader.getResourceAsStream(resource)) {
            resource
        }
        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader -> this.load(reader) }
    }
}
