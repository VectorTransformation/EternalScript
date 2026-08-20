package eternalscript.intellij.model

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class EternalScriptIncrementalPlannerTest {
    @Test
    fun `leading dash on any relative segment disables declaration export`() {
        val root = Path.of("workspace", "scripts")

        assertTrue(EternalScriptIncrementalPlanner.isActivePath(root, root.resolve("folder/test.eternal.kts")))
        assertFalse(EternalScriptIncrementalPlanner.isActivePath(root, root.resolve("folder/-test.eternal.kts")))
        assertFalse(EternalScriptIncrementalPlanner.isActivePath(root, root.resolve("-folder/test.eternal.kts")))
    }

    @Test
    fun `disabled files and directories remain IDE editable but never active`() {
        val root = Path.of("workspace", "scripts")

        assertTrue(EternalScriptIncrementalPlanner.isVisibleToIde(root, root.resolve("folder/test.eternal.kts")))
        assertTrue(EternalScriptIncrementalPlanner.isVisibleToIde(root, root.resolve("folder/-test.eternal.kts")))
        assertTrue(EternalScriptIncrementalPlanner.isVisibleToIde(root, root.resolve("-folder/test.eternal.kts")))
        assertTrue(EternalScriptIncrementalPlanner.isVisibleToIde(root, root.resolve("folder/-nested/test.eternal.kts")))
    }
}
