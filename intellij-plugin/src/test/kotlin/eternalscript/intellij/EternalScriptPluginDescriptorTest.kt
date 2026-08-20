package eternalscript.intellij

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class EternalScriptPluginDescriptorTest {
    @Test
    fun `Kotlin annotator declares the implementation class attribute expected by IDEA`() {
        val resource = assertNotNull(
            EternalScriptPluginDescriptorTest::class.java.classLoader.getResourceAsStream("META-INF/plugin.xml")
        )
        val descriptor = resource.use { input ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        }
        val annotators = descriptor.getElementsByTagName("annotator")
        assertEquals(1, annotators.length)

        val annotator = annotators.item(0) as Element
        assertEquals("kotlin", annotator.getAttribute("language"))
        assertEquals(
            "eternalscript.intellij.diagnostics.EternalScriptConflictAnnotator",
            annotator.getAttribute("implementationClass")
        )
        assertFalse(annotator.hasAttribute("implementation"))
    }

    @Test
    fun `Tools menu exposes all workspace actions`() {
        val resource = assertNotNull(
            EternalScriptPluginDescriptorTest::class.java.classLoader.getResourceAsStream("META-INF/plugin.xml")
        )
        val descriptor = resource.use { input ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        }
        val groups = descriptor.getElementsByTagName("group")
        val tools = (0 until groups.length).asSequence()
            .map { index -> groups.item(index) as Element }
            .single { group -> group.getAttribute("id") == "EternalScript.Tools" }
        val actionClasses = tools.getElementsByTagName("action").let { actions ->
            (0 until actions.length).map { index -> (actions.item(index) as Element).getAttribute("class") }
        }

        assertTrue(
            tools.getElementsByTagName("add-to-group").let { groupsToAdd ->
                (0 until groupsToAdd.length).any { index ->
                    (groupsToAdd.item(index) as Element).getAttribute("group-id") == "ToolsMenu"
                }
            }
        )
        assertEquals(
            listOf(
                "eternalscript.intellij.actions.ReloadEternalScriptEnvironmentAction",
                "eternalscript.intellij.actions.OpenEternalScriptManifestAction",
                "eternalscript.intellij.actions.OpenEternalScriptRootAction",
                "eternalscript.intellij.actions.CopyEternalScriptDiagnosticsAction"
            ),
            actionClasses
        )
    }
}
