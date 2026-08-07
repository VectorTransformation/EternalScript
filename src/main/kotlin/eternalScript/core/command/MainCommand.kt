package eternalScript.core.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import eternalScript.core.feedback.UserFeedback
import eternalScript.core.feedback.UserFeedbackChannels
import eternalScript.core.feedback.UserFeedbackEvent
import eternalScript.core.manager.DataManager
import eternalScript.core.workspace.WorkspaceManager
import io.papermc.paper.command.brigadier.CommandSourceStack

/** Administrative command surface for the one active Kotlin script project. */
internal object MainCommand : CommandBuilder() {
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
        result(DataManager.reload(feedback(context)))

    fun check(context: CommandContext<CommandSourceStack>): Int =
        result(DataManager.check(feedback(context)))

    fun unload(context: CommandContext<CommandSourceStack>): Int =
        result(DataManager.unload(feedback(context)))

    fun list(context: CommandContext<CommandSourceStack>): Int {
        val status = DataManager.projectStatus()
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
                project = DataManager.projectStatus(),
                workspace = WorkspaceManager.status()
            )
        )
        return Command.SINGLE_SUCCESS
    }

    fun workspaceStatus(context: CommandContext<CommandSourceStack>): Int {
        feedback(context).emit(
            UserFeedbackEvent.WorkspaceStatusView(WorkspaceManager.status())
        )
        return Command.SINGLE_SUCCESS
    }

    fun workspaceUpdate(context: CommandContext<CommandSourceStack>): Int =
        result(DataManager.refreshWorkspace(feedback(context)))

    fun reloadConfig(context: CommandContext<CommandSourceStack>): Int =
        result(DataManager.reloadConfig(feedback(context)))

    fun clearCache(context: CommandContext<CommandSourceStack>): Int =
        result(DataManager.clearCache(feedback(context)))

    private fun feedback(context: CommandContext<CommandSourceStack>): UserFeedback =
        UserFeedbackChannels.reply(context.source.sender)

    private fun result(accepted: Boolean): Int =
        if (accepted) Command.SINGLE_SUCCESS else 0

}
