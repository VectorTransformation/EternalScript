package eternalScript.core.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import eternalScript.core.feedback.UserFeedback
import eternalScript.core.feedback.UserFeedbackEvent
import eternalScript.core.manager.DataManager
import eternalScript.core.manager.ScriptProjectStatus
import eternalScript.core.workspace.WorkspaceManager
import eternalScript.core.workspace.WorkspaceStatus
import io.papermc.paper.command.brigadier.CommandSourceStack

/** Administrative command surface for the one active Kotlin script project. */
internal class MainCommand(
    private val projectController: ProjectCommandController,
    private val workspaceStatus: () -> WorkspaceStatus,
    private val feedbackFactory: (org.bukkit.command.CommandSender) -> UserFeedback
) : CommandBuilder() {
    override val builder = builder("eternalscript") {
        requires(::isOp)
        executes(::status)
        then(builder("reload") {
            executes(::reload)
        })
        then(builder("check") {
            executes(::check)
        })
        then(builder("unload") {
            executes(::unload)
        })
        then(builder("list") {
            executes(::list)
        })
        then(builder("status") {
            executes(::status)
        })
        then(builder("workspace") {
            executes(::workspaceStatus)
            then(builder("update") {
                executes(::workspaceUpdate)
            })
        })
        then(builder("config") {
            then(builder("reload") {
                executes(::reloadConfig)
            })
        })
        then(builder("cache") {
            then(builder("clear") {
                executes(::clearCache)
            })
        })
    }
    override val aliases = listOf("es")

    fun reload(context: CommandContext<CommandSourceStack>): Int =
        result(projectController.reload(feedback(context)))

    fun check(context: CommandContext<CommandSourceStack>): Int =
        result(projectController.check(feedback(context)))

    fun unload(context: CommandContext<CommandSourceStack>): Int =
        result(projectController.unload(feedback(context)))

    fun list(context: CommandContext<CommandSourceStack>): Int {
        val status = projectController.projectStatus()
        feedback(context).emit(
            UserFeedbackEvent.ProjectEntries(
                entries = status.generation.entryNames,
                diskSourceCount = status.availableSources.size,
                activeProject = status.generation.exists
            )
        )
        return Command.SINGLE_SUCCESS
    }

    fun status(context: CommandContext<CommandSourceStack>): Int {
        feedback(context).emit(
            UserFeedbackEvent.ProjectStatusView(
                project = projectController.projectStatus(),
                workspace = workspaceStatus()
            )
        )
        return Command.SINGLE_SUCCESS
    }

    fun workspaceStatus(context: CommandContext<CommandSourceStack>): Int {
        feedback(context).emit(
            UserFeedbackEvent.WorkspaceStatusView(workspaceStatus())
        )
        return Command.SINGLE_SUCCESS
    }

    fun workspaceUpdate(context: CommandContext<CommandSourceStack>): Int =
        result(projectController.refreshWorkspace(feedback(context)))

    fun reloadConfig(context: CommandContext<CommandSourceStack>): Int =
        result(projectController.reloadConfig(feedback(context)))

    fun clearCache(context: CommandContext<CommandSourceStack>): Int =
        result(projectController.clearCache(feedback(context)))

    private fun feedback(context: CommandContext<CommandSourceStack>): UserFeedback =
        feedbackFactory(context.source.sender)

    private fun result(accepted: Boolean): Int =
        if (accepted) Command.SINGLE_SUCCESS else 0

}

internal interface ProjectCommandController {
    fun reload(feedback: UserFeedback): Boolean
    fun check(feedback: UserFeedback): Boolean
    fun unload(feedback: UserFeedback): Boolean
    fun refreshWorkspace(feedback: UserFeedback): Boolean
    fun reloadConfig(feedback: UserFeedback): Boolean
    fun clearCache(feedback: UserFeedback): Boolean
    fun projectStatus(): ScriptProjectStatus
}
