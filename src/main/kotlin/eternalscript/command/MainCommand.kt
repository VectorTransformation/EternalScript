package eternalscript.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import eternalscript.api.EternalScriptApi
import eternalscript.api.ScriptDiagnostic
import eternalscript.api.ScriptOperation
import eternalscript.api.ScriptOperationResult
import eternalscript.api.ScriptOperationStatus
import eternalscript.feedback.FeedbackKey
import eternalscript.feedback.FeedbackLevel
import eternalscript.feedback.FeedbackLine
import eternalscript.feedback.FeedbackLineKind
import eternalscript.feedback.FeedbackService
import eternalscript.feedback.FeedbackView
import eternalscript.feedback.feedbackText
import eternalscript.feedback.systemFeedback
import eternalscript.scripting.source.ScriptSourceRepository
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.concurrent.CompletionStage

internal class MainCommand(
    plugin: JavaPlugin,
    private val api: EternalScriptApi,
    private val sources: ScriptSourceRepository,
    private val feedback: FeedbackService,
    private val reloadConfiguration: (CommandSender) -> Unit
) : PaperCommandBuilder(plugin) {
    override val command = literal("eternalscript") {
        executes(::help)
        then(literal("help") { executes(::help) })
        then(literal("reload") { executes(::reload) })
        then(literal("compile") { executes(::compile) })
        then(literal("clear") { executes(::clear) })
        then(literal("config") { executes(::config) })
        then(literal("load") {
            executes(::loadUsage)
            then(argument("script", StringArgumentType.string()) {
                suggests { context, builder ->
                    if (canManage(context.source.sender, PERMISSION_LOAD)) {
                        suggestPaths(sources.knownPaths(), builder)
                    }
                    builder.buildFuture()
                }
                executes(::load)
            })
        })
        then(literal("unload") {
            executes(::unloadUsage)
            then(argument("script", StringArgumentType.string()) {
                suggests { context, builder ->
                    if (canManage(context.source.sender, PERMISSION_UNLOAD)) {
                        suggestPaths(activeTargets(), builder)
                    }
                    builder.buildFuture()
                }
                executes(::unload)
            })
        })
        then(literal("list") {
            executes { context -> list(context, 1) }
            then(argument("page", IntegerArgumentType.integer(1)) {
                executes { context -> list(context, IntegerArgumentType.getInteger(context, "page")) }
            })
        })
        then(literal("status") { executes(::status) })
        then(argument("unknown", StringArgumentType.greedyString()) { executes(::unknown) })
    }
    override val description: String = "Manage EternalScript scripts"
    override val aliases: Collection<String> = listOf("es")

    private data class OperationRequest(
        val operation: ScriptOperation,
        val target: String? = null
    )

    private fun help(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_HELP) ?: return 0
        feedback.send(
            sender,
            FeedbackView(
                FeedbackLevel.INFO,
                feedbackText(FeedbackKey.COMMAND_HELP_HEADER),
                listOf(
                    FeedbackLine(feedbackText(FeedbackKey.COMMAND_HELP_RELOAD)),
                    FeedbackLine(feedbackText(FeedbackKey.COMMAND_HELP_COMPILE)),
                    FeedbackLine(feedbackText(FeedbackKey.COMMAND_HELP_CLEAR)),
                    FeedbackLine(feedbackText(FeedbackKey.COMMAND_HELP_CONFIG)),
                    FeedbackLine(feedbackText(FeedbackKey.COMMAND_HELP_LOAD)),
                    FeedbackLine(feedbackText(FeedbackKey.COMMAND_HELP_UNLOAD)),
                    FeedbackLine(feedbackText(FeedbackKey.COMMAND_HELP_LIST)),
                    FeedbackLine(feedbackText(FeedbackKey.COMMAND_HELP_STATUS))
                )
            )
        )
        return Command.SINGLE_SUCCESS
    }

    private fun reload(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_RELOAD) ?: return 0
        val request = OperationRequest(ScriptOperation.RELOAD)
        submit(sender, request, api.reload())
        return Command.SINGLE_SUCCESS
    }

    private fun compile(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_COMPILE) ?: return 0
        val request = OperationRequest(ScriptOperation.RECOMPILE)
        submit(sender, request, api.recompile())
        return Command.SINGLE_SUCCESS
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_CLEAR) ?: return 0
        val request = OperationRequest(ScriptOperation.CLEAR)
        submit(sender, request, api.clear())
        return Command.SINGLE_SUCCESS
    }

    private fun config(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_CONFIG) ?: return 0
        runCatching { reloadConfiguration(sender) }
            .onFailure { error ->
                val reason = error.message ?: error.javaClass.simpleName
                feedback.send(
                    sender,
                    FeedbackView(
                        FeedbackLevel.ERROR,
                        feedbackText(FeedbackKey.COMMAND_CONFIG_FAILED),
                        listOf(
                            FeedbackLine(
                                feedbackText(FeedbackKey.COMMAND_ERROR_DETAIL, "error" to reason),
                                FeedbackLineKind.ERROR
                            )
                        )
                    )
                )
                feedback.system(
                    systemFeedback(
                        FeedbackLevel.ERROR,
                        FeedbackKey.SYSTEM_CONFIG_RELOAD_FAILED,
                        "error" to reason,
                        cause = error
                    )
                )
            }
        return Command.SINGLE_SUCCESS
    }

    private fun loadUsage(context: CommandContext<CommandSourceStack>): Int =
        usage(context, PERMISSION_LOAD, FeedbackKey.COMMAND_USAGE_LOAD)

    private fun unloadUsage(context: CommandContext<CommandSourceStack>): Int =
        usage(context, PERMISSION_UNLOAD, FeedbackKey.COMMAND_USAGE_UNLOAD)

    private fun usage(
        context: CommandContext<CommandSourceStack>,
        permission: String,
        key: FeedbackKey
    ): Int {
        val sender = operator(context, permission) ?: return 0
        feedback.send(sender, FeedbackView(FeedbackLevel.WARNING, feedbackText(key)))
        return 0
    }

    private fun load(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_LOAD) ?: return 0
        val path = StringArgumentType.getString(context, "script")
        val request = OperationRequest(ScriptOperation.LOAD, path)
        submit(sender, request, api.load(path))
        return Command.SINGLE_SUCCESS
    }

    private fun unload(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_UNLOAD) ?: return 0
        val path = StringArgumentType.getString(context, "script")
        val request = OperationRequest(ScriptOperation.UNLOAD, path)
        submit(sender, request, api.unload(path))
        return Command.SINGLE_SUCCESS
    }

    private fun list(context: CommandContext<CommandSourceStack>, requestedPage: Int): Int {
        val sender = operator(context, PERMISSION_LIST) ?: return 0
        val snapshot = api.snapshot()
        val paths = snapshot.scripts.map { script -> script.path }
        val page = scriptListPage(
            paths,
            requestedPage,
            SCRIPTS_PER_PAGE
        )
        if (page == null) {
            val count = paths.distinct().size
            val pages = maxOf(1, (count + SCRIPTS_PER_PAGE - 1) / SCRIPTS_PER_PAGE)
            feedback.send(
                sender,
                FeedbackView(
                    FeedbackLevel.WARNING,
                    feedbackText(
                        FeedbackKey.COMMAND_LIST_INVALID_PAGE,
                        "page" to requestedPage,
                        "pages" to pages
                    )
                )
            )
            return 0
        }
        if (page.total == 0) {
            feedback.send(
                sender,
                FeedbackView(FeedbackLevel.INFO, feedbackText(FeedbackKey.COMMAND_LIST_EMPTY))
            )
            return Command.SINGLE_SUCCESS
        }
        feedback.send(
            sender,
            FeedbackView(
                FeedbackLevel.INFO,
                feedbackText(
                    FeedbackKey.COMMAND_LIST_HEADER,
                    "count" to page.total,
                    "page" to page.page,
                    "pages" to page.pages
                ),
                page.paths.map { path ->
                    FeedbackLine(feedbackText(FeedbackKey.COMMAND_LIST_ENTRY, "path" to path))
                }
            )
        )
        return Command.SINGLE_SUCCESS
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_STATUS) ?: return 0
        val snapshot = api.snapshot()
        val state = when (snapshot.state) {
            eternalscript.api.ScriptEngineState.STARTING -> feedbackText(FeedbackKey.COMMAND_STATUS_STARTING)
            eternalscript.api.ScriptEngineState.READY -> feedbackText(FeedbackKey.COMMAND_STATUS_READY)
            eternalscript.api.ScriptEngineState.DISABLED -> feedbackText(FeedbackKey.COMMAND_STATUS_DISABLED)
        }
        val operation = snapshot.busyOperation
        val activity = if (operation == null) {
            feedbackText(FeedbackKey.COMMAND_STATUS_IDLE)
        } else {
            feedbackText(FeedbackKey.COMMAND_STATUS_BUSY, "operation" to operation.feedbackText())
        }
        feedback.send(
            sender,
            FeedbackView(
                FeedbackLevel.INFO,
                feedbackText(FeedbackKey.COMMAND_STATUS_HEADER, "state" to state),
                listOf(
                    FeedbackLine(
                        feedbackText(FeedbackKey.COMMAND_STATUS_SCRIPTS, "count" to snapshot.scripts.size)
                    ),
                    FeedbackLine(activity)
                )
            )
        )
        return Command.SINGLE_SUCCESS
    }

    private fun unknown(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_HELP) ?: return 0
        feedback.send(
            sender,
            FeedbackView(
                FeedbackLevel.ERROR,
                feedbackText(
                    FeedbackKey.COMMAND_UNKNOWN,
                    "input" to StringArgumentType.getString(context, "unknown")
                ),
                listOf(FeedbackLine(feedbackText(FeedbackKey.COMMAND_HINT_HELP), FeedbackLineKind.HINT))
            )
        )
        return 0
    }

    private fun submit(
        sender: CommandSender,
        request: OperationRequest,
        stage: CompletionStage<ScriptOperationResult>
    ) {
        if (shouldAcknowledge(stage)) {
            feedback.send(
                sender,
                FeedbackView(
                    FeedbackLevel.INFO,
                    feedbackText(
                        FeedbackKey.COMMAND_OPERATION_ACCEPTED,
                        "operation" to request.operation.feedbackText()
                    )
                )
            )
        }
        stage.whenComplete { result, error ->
            if (error != null) {
                val reason = error.message ?: error.javaClass.simpleName
                feedback.send(
                    sender,
                    FeedbackView(
                        FeedbackLevel.ERROR,
                        feedbackText(
                            FeedbackKey.COMMAND_OPERATION_FAILED,
                            "operation" to request.operation.feedbackText()
                        ),
                        listOf(
                            FeedbackLine(
                                feedbackText(FeedbackKey.COMMAND_ERROR_DETAIL, "error" to reason),
                                FeedbackLineKind.ERROR
                            )
                        )
                    )
                )
                feedback.system(
                    systemFeedback(
                        FeedbackLevel.ERROR,
                        FeedbackKey.SYSTEM_COMMAND_COMPLETION_FAILED,
                        "operation" to request.operation.feedbackText(),
                        "error" to reason,
                        cause = error
                    )
                )
            } else {
                report(sender, request, result)
            }
        }
    }

    private fun report(
        sender: CommandSender,
        request: OperationRequest,
        result: ScriptOperationResult
    ) {
        val level = when (result.status) {
            ScriptOperationStatus.SUCCESS -> FeedbackLevel.SUCCESS
            ScriptOperationStatus.NO_CHANGE -> FeedbackLevel.INFO
            ScriptOperationStatus.BUSY,
            ScriptOperationStatus.NOT_FOUND,
            ScriptOperationStatus.CANCELLED -> FeedbackLevel.WARNING
            ScriptOperationStatus.INVALID_PATH,
            ScriptOperationStatus.FAILED,
            ScriptOperationStatus.DISABLED -> FeedbackLevel.ERROR
        }
        val title = when (result.status) {
            ScriptOperationStatus.SUCCESS -> feedbackText(
                FeedbackKey.COMMAND_OPERATION_SUCCESS,
                "operation" to request.operation.feedbackText()
            )
            ScriptOperationStatus.NO_CHANGE -> feedbackText(
                FeedbackKey.COMMAND_OPERATION_NO_CHANGE,
                "operation" to request.operation.feedbackText()
            )
            ScriptOperationStatus.BUSY -> feedbackText(
                FeedbackKey.COMMAND_OPERATION_BUSY,
                "operation" to request.operation.feedbackText()
            )
            ScriptOperationStatus.NOT_FOUND -> feedbackText(
                FeedbackKey.COMMAND_OPERATION_NOT_FOUND,
                "target" to (result.affectedPaths.firstOrNull() ?: request.target ?: "-")
            )
            ScriptOperationStatus.INVALID_PATH -> feedbackText(
                FeedbackKey.COMMAND_OPERATION_INVALID_PATH,
                "target" to (request.target ?: result.affectedPaths.firstOrNull() ?: "-")
            )
            ScriptOperationStatus.FAILED -> feedbackText(
                FeedbackKey.COMMAND_OPERATION_FAILED,
                "operation" to request.operation.feedbackText()
            )
            ScriptOperationStatus.CANCELLED -> feedbackText(
                FeedbackKey.COMMAND_OPERATION_CANCELLED,
                "operation" to request.operation.feedbackText()
            )
            ScriptOperationStatus.DISABLED -> feedbackText(FeedbackKey.COMMAND_OPERATION_DISABLED)
        }

        val lines = mutableListOf<FeedbackLine>()
        if (result.affectedPaths.isNotEmpty() && result.status != ScriptOperationStatus.NOT_FOUND) {
            lines += FeedbackLine(
                feedbackText(
                    FeedbackKey.COMMAND_OPERATION_AFFECTED,
                    "count" to result.affectedPaths.size,
                    "paths" to summarizePaths(result.affectedPaths)
                )
            )
        }
        result.diagnostics.forEach { diagnostic -> lines += diagnosticLines(diagnostic) }
        hint(result.status)?.let { hint -> lines += FeedbackLine(feedbackText(hint), FeedbackLineKind.HINT) }
        feedback.send(sender, FeedbackView(level, title, lines))
    }

    private fun diagnosticLines(diagnostic: ScriptDiagnostic): List<FeedbackLine> = buildList {
        add(
            FeedbackLine(
                feedbackText(
                    FeedbackKey.COMMAND_DIAGNOSTIC_HEADER,
                    "source" to diagnostic.source,
                    "phase" to diagnostic.phase.feedbackText()
                ),
                FeedbackLineKind.ERROR
            )
        )
        if (diagnostic.line != null || diagnostic.column != null) {
            add(
                FeedbackLine(
                    feedbackText(
                        FeedbackKey.COMMAND_DIAGNOSTIC_LOCATION,
                        "line" to diagnostic.line,
                        "column" to diagnostic.column
                    )
                )
            )
        }
        add(
            FeedbackLine(
                feedbackText(FeedbackKey.COMMAND_DIAGNOSTIC_MESSAGE, "message" to diagnostic.message),
                FeedbackLineKind.ERROR
            )
        )
    }

    private fun hint(status: ScriptOperationStatus): FeedbackKey? = when (status) {
        ScriptOperationStatus.BUSY -> FeedbackKey.COMMAND_OPERATION_HINT_BUSY
        ScriptOperationStatus.NOT_FOUND -> FeedbackKey.COMMAND_OPERATION_HINT_NOT_FOUND
        ScriptOperationStatus.INVALID_PATH -> FeedbackKey.COMMAND_OPERATION_HINT_INVALID_PATH
        ScriptOperationStatus.FAILED -> FeedbackKey.COMMAND_OPERATION_HINT_FAILED
        ScriptOperationStatus.CANCELLED -> FeedbackKey.COMMAND_OPERATION_HINT_CANCELLED
        else -> null
    }

    private fun operator(
        context: CommandContext<CommandSourceStack>,
        permission: String
    ): CommandSender? {
        val sender = context.source.sender
        if (canManage(sender, permission)) return sender
        feedback.send(
            sender,
            FeedbackView(FeedbackLevel.ERROR, feedbackText(FeedbackKey.COMMAND_PERMISSION_DENIED))
        )
        return null
    }

    private fun activeTargets(): List<String> = buildSet {
        api.snapshot().scripts.forEach { script ->
            add(script.path)
            val parts = script.path.split('/')
            for (end in 1 until parts.size) {
                add(parts.take(end).joinToString("/"))
            }
        }
    }.sortedWith(compareBy({ value -> value.lowercase(Locale.ROOT) }, { value -> value }))

    private fun summarizePaths(paths: List<String>): String {
        val sorted = paths.distinct().sortedWith(compareBy({ value -> value.lowercase(Locale.ROOT) }, { it }))
        val shown = sorted.take(MAX_PATHS_IN_SUMMARY).joinToString(", ")
        val remaining = sorted.size - MAX_PATHS_IN_SUMMARY
        return if (remaining > 0) "$shown … +$remaining" else shown
    }

    private companion object {
        const val MAX_PATHS_IN_SUMMARY: Int = 6
        const val SCRIPTS_PER_PAGE: Int = 10
        const val PERMISSION_HELP: String = "eternalscript.command.help"
        const val PERMISSION_RELOAD: String = "eternalscript.command.reload"
        const val PERMISSION_COMPILE: String = "eternalscript.command.compile"
        const val PERMISSION_CLEAR: String = "eternalscript.command.clear"
        const val PERMISSION_CONFIG: String = "eternalscript.command.config"
        const val PERMISSION_LOAD: String = "eternalscript.command.load"
        const val PERMISSION_UNLOAD: String = "eternalscript.command.unload"
        const val PERMISSION_LIST: String = "eternalscript.command.list"
        const val PERMISSION_STATUS: String = "eternalscript.command.status"
    }
}

internal data class ScriptListPage(
    val paths: List<String>,
    val page: Int,
    val pages: Int,
    val total: Int
)

internal fun scriptListPage(
    paths: Iterable<String>,
    requestedPage: Int,
    pageSize: Int
): ScriptListPage? {
    require(pageSize > 0) { "pageSize must be greater than zero" }
    val sorted = paths.distinct().sortedWith(compareBy({ value -> value.lowercase(Locale.ROOT) }, { it }))
    val pages = maxOf(1, (sorted.size + pageSize - 1) / pageSize)
    if (requestedPage !in 1..pages) return null
    val offset = (requestedPage - 1) * pageSize
    return ScriptListPage(
        paths = sorted.drop(offset).take(pageSize),
        page = requestedPage,
        pages = pages,
        total = sorted.size
    )
}

internal fun canManage(sender: CommandSender, permission: String): Boolean =
    sender.isOp || sender.hasPermission("eternalscript.admin") || sender.hasPermission(permission)

internal fun shouldAcknowledge(stage: CompletionStage<*>): Boolean =
    !stage.toCompletableFuture().isDone

internal fun suggestPaths(candidates: Iterable<String>, builder: SuggestionsBuilder) {
    val prefix = builder.remaining
        .removePrefix("\"")
        .removeSuffix("\"")
        .lowercase(Locale.ROOT)

    candidates.asSequence()
        .filter { candidate -> candidate.lowercase(Locale.ROOT).startsWith(prefix) }
        .map { candidate -> StringArgumentType.escapeIfRequired(candidate) }
        .forEach(builder::suggest)
}
