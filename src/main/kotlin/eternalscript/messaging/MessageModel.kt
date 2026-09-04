package eternalscript.messaging

import net.kyori.adventure.text.Component

internal enum class MessageLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

internal enum class MessageLineKind {
    DETAIL,
    HINT,
    ERROR
}

internal enum class MessageKey(
    val id: String,
    vararg placeholderNames: String
) {
    OPERATION_CHECK("operation.check"),
    OPERATION_RELOAD("operation.reload"),
    OPERATION_RECOMPILE("operation.recompile"),
    OPERATION_ENABLE("operation.enable"),
    OPERATION_DISABLE("operation.disable"),
    OPERATION_CANCEL("operation.cancel"),

    PHASE_SOURCE("phase.source"),
    PHASE_COMPILE("phase.compile"),
    PHASE_EVALUATE("phase.evaluate"),
    PHASE_ACTIVATE("phase.activate"),
    PHASE_ROLLBACK("phase.rollback"),

    COMMAND_PERMISSION_DENIED("command.permission_denied"),
    COMMAND_UNKNOWN("command.unknown", "input"),
    COMMAND_HELP_CHECK("command.help.check"),
    COMMAND_HELP_RELOAD("command.help.reload"),
    COMMAND_HELP_ENABLE("command.help.enable"),
    COMMAND_HELP_DISABLE("command.help.disable"),
    COMMAND_HELP_CANCEL("command.help.cancel"),
    COMMAND_HELP_CONFIG("command.help.config"),
    COMMAND_HELP_LIST("command.help.list"),
    COMMAND_USAGE_ENABLE("command.usage.enable"),
    COMMAND_USAGE_DISABLE("command.usage.disable"),
    COMMAND_USAGE_CONFIG("command.usage.config"),
    COMMAND_HINT_HELP("command.hint.help"),

    COMMAND_CONFIG_SUCCESS("command.config.success"),
    COMMAND_CONFIG_WITH_ISSUES("command.config.with_issues", "count"),
    COMMAND_CONFIG_ISSUE("command.config.issue", "file", "reason"),
    COMMAND_CONFIG_MORE_ISSUES("command.config.more_issues", "count"),
    COMMAND_CONFIG_FAILED("command.config.failed"),
    COMMAND_ERROR_DETAIL("command.error.detail", "error"),

    COMMAND_OPERATION_ACCEPTED("command.operation.accepted", "operation"),
    COMMAND_OPERATION_SUCCESS("command.operation.success", "operation"),
    COMMAND_OPERATION_NO_CHANGE("command.operation.no_change", "operation"),
    COMMAND_OPERATION_BUSY("command.operation.busy", "operation"),
    COMMAND_OPERATION_NOT_FOUND("command.operation.not_found", "target"),
    COMMAND_OPERATION_INVALID_PATH("command.operation.invalid_path", "target"),
    COMMAND_OPERATION_FAILED("command.operation.failed", "operation"),
    COMMAND_OPERATION_CANCELLED("command.operation.cancelled", "operation"),
    COMMAND_OPERATION_DISABLED("command.operation.disabled"),
    COMMAND_OPERATION_AFFECTED("command.operation.affected", "count", "paths"),
    COMMAND_OPERATION_HINT_BUSY("command.operation.hint.busy"),
    COMMAND_OPERATION_HINT_NOT_FOUND("command.operation.hint.not_found"),
    COMMAND_OPERATION_HINT_INVALID_PATH("command.operation.hint.invalid_path"),
    COMMAND_OPERATION_HINT_FAILED("command.operation.hint.failed"),
    COMMAND_OPERATION_HINT_CANCELLED("command.operation.hint.cancelled"),

    COMMAND_LIST_HEADER("command.list.header", "filter", "count", "page", "pages"),
    COMMAND_LIST_EMPTY("command.list.empty", "filter"),
    COMMAND_LIST_ENTRY("command.list.entry", "path"),
    COMMAND_LIST_INVALID_PAGE("command.list.invalid_page", "page", "pages"),

    COMMAND_STATUS_HEADER("command.status.header", "state"),
    COMMAND_STATUS_STARTING("command.status.starting"),
    COMMAND_STATUS_READY("command.status.ready"),
    COMMAND_STATUS_DISABLED("command.status.disabled"),
    COMMAND_STATUS_SCRIPTS("command.status.scripts", "count"),
    COMMAND_STATUS_IDLE("command.status.idle"),
    COMMAND_STATUS_BUSY("command.status.busy", "operation"),

    COMMAND_DIAGNOSTIC_HEADER("command.diagnostic.header", "source", "phase"),
    COMMAND_DIAGNOSTIC_LOCATION("command.diagnostic.location", "line", "column"),
    COMMAND_DIAGNOSTIC_MESSAGE("command.diagnostic.message", "message"),

    SYSTEM_STARTUP_SUCCESS("system.startup.success", "count"),
    SYSTEM_STARTUP_DEGRADED("system.startup.degraded"),
    SYSTEM_SHUTDOWN_SUCCESS("system.shutdown.success"),
    SYSTEM_CATALOG_ISSUE("system.catalog.issue", "file", "reason"),
    SYSTEM_CONFIG_RELOAD_FAILED("system.config.reload_failed", "error"),
    SYSTEM_COMMAND_COMPLETION_FAILED("system.command.completion_failed", "operation", "error"),
    SYSTEM_PATH_ROLLBACK_FAILED("system.path.rollback_failed", "target", "error"),
    SYSTEM_SUGGESTIONS_REFRESH_FAILED("system.suggestions.refresh_failed", "error"),
    SYSTEM_OPERATION_UNEXPECTED("system.operation.unexpected", "operation", "error"),
    SYSTEM_CACHE_PUBLISH_FAILED("system.cache.publish_failed", "error"),
    SYSTEM_IDE_ENVIRONMENT_PUBLISH_FAILED("system.ide.environment_publish_failed", "error"),
    SYSTEM_COMMAND_TREE_REFRESH_FAILED("system.command_tree.refresh_failed", "player", "error"),
    SYSTEM_DIAGNOSTIC(
        "system.diagnostic",
        "source",
        "phase",
        "line",
        "column",
        "message"
    ),
    SYSTEM_SCRIPT_CLEANUP_FAILED("system.script.cleanup_failed", "source", "error"),
    SYSTEM_SCRIPT_CLEANUP_ADDITIONAL("system.script.cleanup_additional", "source", "error"),
    SYSTEM_SCRIPT_FAILURE_CAUSE(
        "system.script.failure_cause",
        "source",
        "message"
    );

    val placeholders: Set<String> = placeholderNames.toSet()
}

internal sealed interface MessageValue {
    data class Literal(val value: String) : MessageValue
    data class Rich(val value: Component) : MessageValue
    data class Nested(val value: MessageText) : MessageValue
}

internal data class MessageText(
    val key: MessageKey,
    val arguments: Map<String, MessageValue> = emptyMap()
) {
    init {
        require(arguments.keys == key.placeholders) {
            "Message '${key.id}' requires ${key.placeholders.sorted()}, received ${arguments.keys.sorted()}"
        }
    }
}

internal data class MessageLine(
    val text: MessageText,
    val kind: MessageLineKind = MessageLineKind.DETAIL
)

internal data class MessageView(
    val level: MessageLevel,
    val title: MessageText,
    val lines: List<MessageLine> = emptyList()
)

internal data class SystemMessage(
    val level: MessageLevel,
    val text: MessageText,
    val cause: Throwable? = null
)

internal fun messageText(
    key: MessageKey,
    vararg arguments: Pair<String, Any?>
): MessageText {
    val values = linkedMapOf<String, MessageValue>()
    arguments.forEach { (name, value) ->
        require(values.put(name, value.toMessageValue()) == null) {
            "Message '${key.id}' received duplicate argument '$name'"
        }
    }
    return MessageText(key, values)
}

internal fun systemMessage(
    level: MessageLevel,
    key: MessageKey,
    vararg arguments: Pair<String, Any?>,
    cause: Throwable? = null
): SystemMessage = SystemMessage(level, messageText(key, *arguments), cause)

private fun Any?.toMessageValue(): MessageValue = when (this) {
    is MessageValue -> this
    is MessageText -> MessageValue.Nested(this)
    is Component -> MessageValue.Rich(this)
    null -> MessageValue.Literal("-")
    else -> MessageValue.Literal(toString())
}
