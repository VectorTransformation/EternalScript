package eternalScript.core.command

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MainCommandStructureTest {
    @Test
    fun `project commands execute directly without all or source scopes`() {
        val root = MainCommand.builder.build()

        listOf("reload", "check", "unload").forEach { name ->
            val command = assertNotNull(root.getChild(name))
            assertNotNull(command.command)
            assertNull(command.getChild("all"))
            assertNull(command.getChild("script"))
        }
    }

    @Test
    fun `workspace status executes directly and update remains explicit`() {
        val workspace = assertNotNull(MainCommand.builder.build().getChild("workspace"))

        assertNotNull(workspace.command)
        assertNotNull(workspace.getChild("update"))
        assertNull(workspace.getChild("status"))
    }
}
