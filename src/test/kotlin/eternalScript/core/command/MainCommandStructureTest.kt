package eternalScript.core.command

import eternalScript.core.feedback.UserFeedback
import eternalScript.core.manager.ScriptProjectStatus
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MainCommandStructureTest {
    private val mainCommand = MainCommand(
        projectController = object : ProjectCommandController {
            override fun reload(feedback: UserFeedback): Boolean = notInvoked()
            override fun check(feedback: UserFeedback): Boolean = notInvoked()
            override fun unload(feedback: UserFeedback): Boolean = notInvoked()
            override fun refreshWorkspace(feedback: UserFeedback): Boolean = notInvoked()
            override fun reloadConfig(feedback: UserFeedback): Boolean = notInvoked()
            override fun clearCache(feedback: UserFeedback): Boolean = notInvoked()
            override fun projectStatus(): ScriptProjectStatus = notInvoked()
        },
        workspaceStatus = { notInvoked() },
        feedbackFactory = { notInvoked() }
    )

    @Test
    fun `project commands execute directly without all or source scopes`() {
        val root = mainCommand.builder.build()

        listOf("reload", "check", "unload").forEach { name ->
            val command = assertNotNull(root.getChild(name))
            assertNotNull(command.command)
            assertNull(command.getChild("all"))
            assertNull(command.getChild("script"))
        }
    }

    @Test
    fun `workspace status executes directly and update remains explicit`() {
        val workspace = assertNotNull(mainCommand.builder.build().getChild("workspace"))

        assertNotNull(workspace.command)
        assertNotNull(workspace.getChild("update"))
        assertNull(workspace.getChild("status"))
    }

    private fun <T> notInvoked(): T = error("Command handlers are not invoked by this test.")
}
