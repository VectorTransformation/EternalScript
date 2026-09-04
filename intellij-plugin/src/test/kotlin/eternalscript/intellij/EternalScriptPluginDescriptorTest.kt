package eternalscript.intellij

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class EternalScriptPluginDescriptorTest {
    @Test
    fun `plugin descriptor does not replace IntelliJ editor features`() {
        val resource = assertNotNull(
            EternalScriptPluginDescriptorTest::class.java.classLoader.getResourceAsStream("META-INF/plugin.xml")
        )
        val descriptor = resource.use { input ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        }

        assertEquals(0, descriptor.getElementsByTagName("annotator").length)
        assertEquals(0, descriptor.getElementsByTagName("referencesSearch").length)
        assertEquals(0, descriptor.getElementsByTagName("renamePsiElementProcessor").length)
    }

    @Test
    fun `plugin descriptor exposes no user actions`() {
        val resource = assertNotNull(
            EternalScriptPluginDescriptorTest::class.java.classLoader.getResourceAsStream("META-INF/plugin.xml")
        )
        val descriptor = resource.use { input ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        }

        assertEquals(0, descriptor.getElementsByTagName("actions").length)
        assertEquals(0, descriptor.getElementsByTagName("action").length)
    }
}
