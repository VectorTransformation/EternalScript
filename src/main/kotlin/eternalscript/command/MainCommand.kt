package eternalscript.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import eternalscript.api.EternalScriptApi
import eternalscript.api.ScriptDiagnostic
import eternalscript.api.ScriptEngineState
import eternalscript.api.ScriptOperation
import eternalscript.api.ScriptOperationResult
import eternalscript.api.ScriptOperationStatus
import eternalscript.messaging.MessageKey
import eternalscript.messaging.MessageLevel
import eternalscript.messaging.MessageLine
import eternalscript.messaging.MessageLineKind
import eternalscript.messaging.MessageView
import eternalscript.messaging.messageText
import eternalscript.messaging.systemMessage
import eternalscript.messaging.UnifiedMessagingService
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
    private val messaging: UnifiedMessagingService,
    private val reloadConfiguration: (CommandSender) -> Unit
) : PaperCommandBuilder(plugin) {
    override val command = literal("eternalscript") {
        executes(::dashboard)
        then(operationWithOptionalTarget("check", PERMISSION_CHECK, ::check, ::enabledDiskTargets))
        then(operationWithOptionalTarget("reload", PERMISSION_RELOAD, ::reload, ::activeTargets))
        then(operationWithRequiredTarget("enable", PERMISSION_ENABLE, ::enable, ::enableTargets))
        then(operationWithRequiredTarget("disable", PERMISSION_DISABLE, ::disable, ::activeTargets))
        then(literal("cancel") {
            requires { source -> canManage(source.sender, PERMISSION_CANCEL) }
            executes(::cancel)
        })
        then(literal("config") {
            requires { source -> canManage(source.sender, PERMISSION_CONFIG) }
            then(literal("reload") { executes(::configReload) })
            executes { context -> usage(context, PERMISSION_CONFIG, MessageKey.COMMAND_USAGE_CONFIG) }
        })
        then(listCommand())
        then(argument("unknown", StringArgumentType.greedyString()) { executes(::unknown) })
    }
    override val description: String = "Manage EternalScript scripts"
    override val aliases: Collection<String> = listOf("es")

    private data class OperationRequest(val operation: ScriptOperation, val target: String? = null)

    private fun operationWithOptionalTarget(
        name: String,
        permission: String,
        action: (CommandContext<CommandSourceStack>, String?) -> Int,
        candidates: () -> List<String>
    ) = literal(name) {
        requires { source -> canManage(source.sender, permission) }
        executes { context -> action(context, null) }
        then(argument("target", StringArgumentType.string()) {
            suggests { _, builder -> suggestPaths(candidates(), builder); builder.buildFuture() }
            executes { context -> action(context, StringArgumentType.getString(context, "target")) }
        })
    }

    private fun operationWithRequiredTarget(
        name: String,
        permission: String,
        action: (CommandContext<CommandSourceStack>, String) -> Int,
        candidates: () -> List<String>
    ) = literal(name) {
        requires { source -> canManage(source.sender, permission) }
        executes { context ->
            usage(
                context,
                permission,
                if (name == "enable") MessageKey.COMMAND_USAGE_ENABLE else MessageKey.COMMAND_USAGE_DISABLE
            )
        }
        then(argument("target", StringArgumentType.string()) {
            suggests { _, builder -> suggestPaths(candidates(), builder); builder.buildFuture() }
            executes { context -> action(context, StringArgumentType.getString(context, "target")) }
        })
    }

    private fun listCommand() = literal("list") {
        requires { source -> canManage(source.sender, PERMISSION_LIST) }
        executes { context -> list(context, ScriptListFilter.ACTIVE, 1) }
        then(argument("page", IntegerArgumentType.integer(1)) {
            executes { context ->
                list(context, ScriptListFilter.ACTIVE, IntegerArgumentType.getInteger(context, "page"))
            }
        })
        ScriptListFilter.entries.forEach { filter ->
            then(literal(filter.argument) {
                executes { context -> list(context, filter, 1) }
                then(argument("page", IntegerArgumentType.integer(1)) {
                    executes { context -> list(context, filter, IntegerArgumentType.getInteger(context, "page")) }
                })
            })
        }
    }

    private fun dashboard(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_LIST) ?: return 0
        val snapshot = api.snapshot()
        val state = when (snapshot.state) {
            ScriptEngineState.STARTING -> messageText(MessageKey.COMMAND_STATUS_STARTING)
            ScriptEngineState.READY -> messageText(MessageKey.COMMAND_STATUS_READY)
            ScriptEngineState.DISABLED -> messageText(MessageKey.COMMAND_STATUS_DISABLED)
        }
        val activity = snapshot.busyOperation?.let { operation ->
            messageText(MessageKey.COMMAND_STATUS_BUSY, "operation" to operation.messageText())
        } ?: messageText(MessageKey.COMMAND_STATUS_IDLE)
        messaging.send(
            sender,
            MessageView(
                MessageLevel.INFO,
                messageText(MessageKey.COMMAND_STATUS_HEADER, "state" to state),
                listOf(
                    MessageLine(messageText(MessageKey.COMMAND_STATUS_SCRIPTS, "count" to snapshot.scripts.size)),
                    MessageLine(activity),
                    MessageLine(messageText(MessageKey.COMMAND_HELP_LIST)),
                    MessageLine(messageText(MessageKey.COMMAND_HELP_CHECK)),
                    MessageLine(messageText(MessageKey.COMMAND_HELP_RELOAD)),
                    MessageLine(messageText(MessageKey.COMMAND_HELP_ENABLE)),
                    MessageLine(messageText(MessageKey.COMMAND_HELP_DISABLE)),
                    MessageLine(messageText(MessageKey.COMMAND_HELP_CANCEL)),
                    MessageLine(messageText(MessageKey.COMMAND_HELP_CONFIG))
                )
            )
        )
        return Command.SINGLE_SUCCESS
    }

    private fun check(context: CommandContext<CommandSourceStack>, target: String?): Int =
        submit(
            context,
            PERMISSION_CHECK,
            OperationRequest(ScriptOperation.CHECK, target),
            if (target == null) api.check() else api.check(target)
        )

    private fun reload(context: CommandContext<CommandSourceStack>, target: String?): Int =
        submit(
            context,
            PERMISSION_RELOAD,
            OperationRequest(ScriptOperation.RELOAD, target),
            if (target == null) api.reload() else api.reload(target)
        )

    private fun enable(context: CommandContext<CommandSourceStack>, target: String): Int =
        submit(context, PERMISSION_ENABLE, OperationRequest(ScriptOperation.ENABLE, target), api.enable(target))

    private fun disable(context: CommandContext<CommandSourceStack>, target: String): Int =
        submit(context, PERMISSION_DISABLE, OperationRequest(ScriptOperation.DISABLE, target), api.disable(target))

    private fun cancel(context: CommandContext<CommandSourceStack>): Int =
        submit(context, PERMISSION_CANCEL, OperationRequest(ScriptOperation.CANCEL), api.cancel())

    private fun submit(
        context: CommandContext<CommandSourceStack>,
        permission: String,
        request: OperationRequest,
        stage: CompletionStage<ScriptOperationResult>
    ): Int {
        val sender = operator(context, permission) ?: return 0
        submit(sender, request, stage)
        return Command.SINGLE_SUCCESS
    }

    private fun configReload(context: CommandContext<CommandSourceStack>): Int {
        val sender = operator(context, PERMISSION_CONFIG) ?: return 0
        runCatching { reloadConfiguration(sender) }.onFailure { error ->
            val reason = error.message ?: error.javaClass.simpleName
            messaging.send(
                sender,
                MessageView(
                    MessageLevel.ERROR,
                    messageText(MessageKey.COMMAND_CONFIG_FAILED),
                    listOf(MessageLine(messageText(MessageKey.COMMAND_ERROR_DETAIL, "error" to reason), MessageLineKind.ERROR))
                )
            )
            messaging.system(
                systemMessage(
                    MessageLevel.ERROR,
                    MessageKey.SYSTEM_CONFIG_RELOAD_FAILED,
                    "error" to reason,
                    cause = error
                )
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun usage(context: CommandContext<CommandSourceStack>, permission: String, key: MessageKey): Int {
        val sender = operator(context, permission) ?: return 0
        messaging.send(sender, MessageView(MessageLevel.WARNING, messageText(key)))
        return 0
    }

    private fun list(context: CommandContext<CommandSourceStack>, filter: ScriptListFilter, requestedPage: Int): Int {
        val sender = operator(context, PERMISSION_LIST) ?: return 0
        val paths = when (filter) {
            ScriptListFilter.ACTIVE -> api.snapshot().scripts.map { it.path }
            ScriptListFilter.DISABLED -> sources.knownTargets().filterNot { it.enabled }.map { it.path }
            ScriptListFilter.ALL -> sources.knownTargets().map { it.path }
        }
        val page = scriptListPage(paths, requestedPage, SCRIPTS_PER_PAGE)
        if (page == null) {
            val pages = maxOf(1, (paths.distinct().size + SCRIPTS_PER_PAGE - 1) / SCRIPTS_PER_PAGE)
            messaging.send(sender, MessageView(MessageLevel.WARNING, messageText(MessageKey.COMMAND_LIST_INVALID_PAGE, "page" to requestedPage, "pages" to pages)))
            return 0
        }
        if (page.total == 0) {
            messaging.send(sender, MessageView(MessageLevel.INFO, messageText(MessageKey.COMMAND_LIST_EMPTY, "filter" to filter.argument)))
            return Command.SINGLE_SUCCESS
        }
        messaging.send(
            sender,
            MessageView(
                MessageLevel.INFO,
                messageText(MessageKey.COMMAND_LIST_HEADER, "filter" to filter.argument, "count" to page.total, "page" to page.page, "pages" to page.pages),
                page.paths.map { path -> MessageLine(messageText(MessageKey.COMMAND_LIST_ENTRY, "path" to path)) }
            )
        )
        return Command.SINGLE_SUCCESS
    }

    private fun unknown(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        messaging.send(
            sender,
            MessageView(
                MessageLevel.ERROR,
                messageText(MessageKey.COMMAND_UNKNOWN, "input" to StringArgumentType.getString(context, "unknown")),
                listOf(MessageLine(messageText(MessageKey.COMMAND_HINT_HELP), MessageLineKind.HINT))
            )
        )
        return 0
    }

    private fun submit(sender: CommandSender, request: OperationRequest, stage: CompletionStage<ScriptOperationResult>) {
        if (shouldAcknowledge(stage)) {
            messaging.send(sender, MessageView(MessageLevel.INFO, messageText(MessageKey.COMMAND_OPERATION_ACCEPTED, "operation" to request.operation.messageText())))
        }
        stage.whenComplete { result, error ->
            if (error != null) {
                val reason = error.message ?: error.javaClass.simpleName
                messaging.send(
                    sender,
                    MessageView(
                        MessageLevel.ERROR,
                        messageText(MessageKey.COMMAND_OPERATION_FAILED, "operation" to request.operation.messageText()),
                        listOf(MessageLine(messageText(MessageKey.COMMAND_ERROR_DETAIL, "error" to reason), MessageLineKind.ERROR))
                    )
                )
                messaging.system(systemMessage(MessageLevel.ERROR, MessageKey.SYSTEM_COMMAND_COMPLETION_FAILED, "operation" to request.operation.messageText(), "error" to reason, cause = error))
            } else {
                report(sender, request, result)
            }
        }
    }

    private fun report(sender: CommandSender, request: OperationRequest, result: ScriptOperationResult) {
        val level = when (result.status) {
            ScriptOperationStatus.SUCCESS -> MessageLevel.SUCCESS
            ScriptOperationStatus.NO_CHANGE -> MessageLevel.INFO
            ScriptOperationStatus.BUSY, ScriptOperationStatus.NOT_FOUND, ScriptOperationStatus.CANCELLED -> MessageLevel.WARNING
            ScriptOperationStatus.INVALID_PATH, ScriptOperationStatus.FAILED, ScriptOperationStatus.DISABLED -> MessageLevel.ERROR
        }
        val title = when (result.status) {
            ScriptOperationStatus.SUCCESS -> messageText(MessageKey.COMMAND_OPERATION_SUCCESS, "operation" to request.operation.messageText())
            ScriptOperationStatus.NO_CHANGE -> messageText(MessageKey.COMMAND_OPERATION_NO_CHANGE, "operation" to request.operation.messageText())
            ScriptOperationStatus.BUSY -> messageText(MessageKey.COMMAND_OPERATION_BUSY, "operation" to request.operation.messageText())
            ScriptOperationStatus.NOT_FOUND -> messageText(MessageKey.COMMAND_OPERATION_NOT_FOUND, "target" to (result.affectedPaths.firstOrNull() ?: request.target ?: "-"))
            ScriptOperationStatus.INVALID_PATH -> messageText(MessageKey.COMMAND_OPERATION_INVALID_PATH, "target" to (request.target ?: result.affectedPaths.firstOrNull() ?: "-"))
            ScriptOperationStatus.FAILED -> messageText(MessageKey.COMMAND_OPERATION_FAILED, "operation" to request.operation.messageText())
            ScriptOperationStatus.CANCELLED -> messageText(MessageKey.COMMAND_OPERATION_CANCELLED, "operation" to request.operation.messageText())
            ScriptOperationStatus.DISABLED -> messageText(MessageKey.COMMAND_OPERATION_DISABLED)
        }
        val lines = mutableListOf<MessageLine>()
        if (result.affectedPaths.isNotEmpty() && result.status != ScriptOperationStatus.NOT_FOUND) {
            lines += MessageLine(messageText(MessageKey.COMMAND_OPERATION_AFFECTED, "count" to result.affectedPaths.size, "paths" to summarizePaths(result.affectedPaths)))
        }
        result.diagnostics.forEach { diagnostic -> lines += diagnosticLines(diagnostic) }
        hint(result.status)?.let { lines += MessageLine(messageText(it), MessageLineKind.HINT) }
        messaging.send(sender, MessageView(level, title, lines))
    }

    private fun diagnosticLines(diagnostic: ScriptDiagnostic): List<MessageLine> = buildList {
        add(MessageLine(messageText(MessageKey.COMMAND_DIAGNOSTIC_HEADER, "source" to diagnostic.source, "phase" to diagnostic.phase.messageText()), MessageLineKind.ERROR))
        if (diagnostic.line != null || diagnostic.column != null) {
            add(MessageLine(messageText(MessageKey.COMMAND_DIAGNOSTIC_LOCATION, "line" to diagnostic.line, "column" to diagnostic.column)))
        }
        add(MessageLine(messageText(MessageKey.COMMAND_DIAGNOSTIC_MESSAGE, "message" to diagnostic.message), MessageLineKind.ERROR))
    }

    private fun hint(status: ScriptOperationStatus): MessageKey? = when (status) {
        ScriptOperationStatus.BUSY -> MessageKey.COMMAND_OPERATION_HINT_BUSY
        ScriptOperationStatus.NOT_FOUND -> MessageKey.COMMAND_OPERATION_HINT_NOT_FOUND
        ScriptOperationStatus.INVALID_PATH -> MessageKey.COMMAND_OPERATION_HINT_INVALID_PATH
        ScriptOperationStatus.FAILED -> MessageKey.COMMAND_OPERATION_HINT_FAILED
        ScriptOperationStatus.CANCELLED -> MessageKey.COMMAND_OPERATION_HINT_CANCELLED
        else -> null
    }

    private fun operator(context: CommandContext<CommandSourceStack>, permission: String): CommandSender? {
        val sender = context.source.sender
        if (canManage(sender, permission)) return sender
        messaging.send(sender, MessageView(MessageLevel.ERROR, messageText(MessageKey.COMMAND_PERMISSION_DENIED)))
        return null
    }

    private fun activeTargets(): List<String> = buildSet {
        api.snapshot().scripts.forEach { script ->
            add(script.path)
            val parts = script.path.split('/')
            for (end in 1 until parts.size) add(parts.take(end).joinToString("/"))
        }
    }.sortedWith(pathComparator)

    private fun enabledDiskTargets(): List<String> = sources.knownTargets().filter { it.enabled }.map { it.path }

    private fun enableTargets(): List<String> {
        val active = activeTargets().toSet()
        return sources.knownTargets().filter { !it.enabled || it.path !in active }.map { it.path }
    }

    private fun summarizePaths(paths: List<String>): String {
        val sorted = paths.distinct().sortedWith(pathComparator)
        val shown = sorted.take(MAX_PATHS_IN_SUMMARY).joinToString(", ")
        val remaining = sorted.size - MAX_PATHS_IN_SUMMARY
        return if (remaining > 0) "$shown …+$remaining" else shown
    }

    private companion object {
        val pathComparator: Comparator<String> = compareBy({ it.lowercase(Locale.ROOT) }, { it })
        const val MAX_PATHS_IN_SUMMARY = 6
        const val SCRIPTS_PER_PAGE = 10
        const val PERMISSION_CHECK = "eternalscript.command.check"
        const val PERMISSION_RELOAD = "eternalscript.command.reload"
        const val PERMISSION_ENABLE = "eternalscript.command.enable"
        const val PERMISSION_DISABLE = "eternalscript.command.disable"
        const val PERMISSION_CANCEL = "eternalscript.command.cancel"
        const val PERMISSION_CONFIG = "eternalscript.command.config"
        const val PERMISSION_LIST = "eternalscript.command.list"
    }
}

internal enum class ScriptListFilter(val argument: String) {
    ACTIVE("active"),
    DISABLED("disabled"),
    ALL("all")
}

internal data class ScriptListPage(val paths: List<String>, val page: Int, val pages: Int, val total: Int)

internal fun scriptListPage(paths: Iterable<String>, requestedPage: Int, pageSize: Int): ScriptListPage? {
    require(pageSize > 0) { "pageSize must be greater than zero" }
    val sorted = paths.distinct().sortedWith(compareBy({ it.lowercase(Locale.ROOT) }, { it }))
    val pages = maxOf(1, (sorted.size + pageSize - 1) / pageSize)
    if (requestedPage !in 1..pages) return null
    val offset = (requestedPage - 1) * pageSize
    return ScriptListPage(sorted.drop(offset).take(pageSize), requestedPage, pages, sorted.size)
}

internal fun canManage(sender: CommandSender, permission: String): Boolean =
    sender.isOp || sender.hasPermission("eternalscript.admin") || sender.hasPermission(permission)

internal fun shouldAcknowledge(stage: CompletionStage<*>): Boolean = !stage.toCompletableFuture().isDone

internal fun suggestPaths(candidates: Iterable<String>, builder: SuggestionsBuilder) {
    val prefix = builder.remaining.removePrefix("\"").removeSuffix("\"").lowercase(Locale.ROOT)
    candidates.asSequence()
        .filter { it.lowercase(Locale.ROOT).startsWith(prefix) }
        .map(StringArgumentType::escapeIfRequired)
        .forEach(builder::suggest)
}
