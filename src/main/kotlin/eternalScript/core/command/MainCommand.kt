package eternalScript.core.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eternalScript.api.command.CommandBuilder
import eternalScript.core.data.Resource
import eternalScript.core.extension.wrap
import eternalScript.core.manager.DataManager
import eternalScript.core.manager.LangManager
import eternalScript.core.manager.ReloadManager
import eternalScript.core.manager.ScriptManager
import eternalScript.core.script.definition.ScriptImportCache
import eternalScript.core.the.Root
import io.papermc.paper.command.brigadier.CommandSourceStack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

object MainCommand : CommandBuilder() {
    override val builder = builder("eternalscript") {
        requires(::isOp)
        executes(::status)
        then(builder("reload") {
            then(builder("all") {
                executes(::reloadAll)
            })
            then(builder("script", StringArgumentType.string()) {
                suggests { _, builder ->
                    (DataManager.scriptPaths() + ScriptImportCache.importPaths())
                        .distinct()
                        .map(String::wrap)
                        .filter {
                            it.lowercase().startsWith(builder.remainingLowerCase)
                        }.forEach {
                            builder.suggest(it)
                        }
                    builder.buildFuture()
                }
                executes(::reloadScript)
            })
        })
        then(builder("unload") {
            then(builder("all") {
                executes(::unloadAll)
            })
            then(builder("script", StringArgumentType.string()) {
                suggests { _, builder ->
                    ScriptManager.scripts().map(String::wrap).filter {
                        it.lowercase().startsWith(builder.remainingLowerCase)
                    }.forEach {
                        builder.suggest(it)
                    }
                    builder.buildFuture()
                }
                executes(::unloadScript)
            })
        })
        then(builder("list") {
            executes(::list)
        })
        then(builder("status") {
            executes(::status)
        })
        then(builder("check") {
            then(builder("all") {
                executes(::checkAll)
            })
            then(builder("script", StringArgumentType.string()) {
                suggests { _, builder ->
                    DataManager.scriptPaths().map(String::wrap).filter {
                        it.lowercase().startsWith(builder.remainingLowerCase)
                    }.forEach {
                        builder.suggest(it)
                    }
                    builder.buildFuture()
                }
                executes(::checkScript)
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

    fun reloadAll(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        DataManager.compile(sender)
        return Command.SINGLE_SUCCESS
    }

    fun reloadScript(context: CommandContext<CommandSourceStack>): Int {
        val script = StringArgumentType.getString(context, "script")
        val sender = context.source.sender
        val available = DataManager.scriptPaths().toSet()
        val targets = buildSet {
            if (script in available) add(script)
            addAll(ScriptImportCache.dependents(script).filter(available::contains))
        }.sorted()

        if (targets.isEmpty()) {
            LangManager.sendMessage(sender, "script.error.not_found", args = listOf(script.wrap()))
            return Command.SINGLE_SUCCESS
        }

        val dependentReload = targets.size > 1 || targets.single() != script
        if (dependentReload) {
            LangManager.sendMessage(
                sender,
                "script.reload.dependents_started",
                args = listOf(script.wrap(), targets.size.toString())
            )
        } else {
            LangManager.sendMessage(sender, "script.reload.one_started", args = listOf(script.wrap()))
        }

        Root.launch {
            val results = targets.map { target ->
                async {
                    DataManager.loadAsync(Resource.SCRIPTS.child(target), sender)
                }
            }.awaitAll()
            val success = results.count { it == true }

            if (dependentReload) {
                LangManager.sendMessage(
                    sender,
                    "script.reload.dependents_completed",
                    args = listOf(
                        script.wrap(),
                        targets.size.toString(),
                        success.toString(),
                        (targets.size - success).toString()
                    )
                )
            } else {
                when (results.single()) {
                    true -> LangManager.sendMessage(sender, "script.reload.completed", args = listOf(script.wrap()))
                    false -> LangManager.sendMessage(sender, "script.reload.failed", args = listOf(script.wrap()))
                    null -> {}
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    fun unloadAll(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        ScriptManager.clear(sender)
        return Command.SINGLE_SUCCESS
    }

    fun unloadScript(context: CommandContext<CommandSourceStack>): Int {
        val script = StringArgumentType.getString(context, "script")
        val sender = context.source.sender
        ScriptManager.remove(script, sender)
        return Command.SINGLE_SUCCESS
    }

    fun list(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        ScriptManager.scriptList(sender)
        return Command.SINGLE_SUCCESS
    }

    fun status(context: CommandContext<CommandSourceStack>): Int {
        val loaded = ScriptManager.scripts().size
        val available = DataManager.scriptPaths().count()
        val stateKey = if (DataManager.isActive()) "script.state.busy" else "script.state.idle"
        val state = LangManager.translatable(stateKey)
        LangManager.sendMessage(
            context.source.sender,
            "script.status",
            args = listOf(state, loaded.toString(), available.toString())
        )
        return Command.SINGLE_SUCCESS
    }

    fun checkAll(context: CommandContext<CommandSourceStack>): Int {
        DataManager.checkAll(context.source.sender)
        return Command.SINGLE_SUCCESS
    }

    fun checkScript(context: CommandContext<CommandSourceStack>): Int {
        val script = StringArgumentType.getString(context, "script")
        val sender = context.source.sender
        if (script !in DataManager.scriptPaths()) {
            LangManager.sendMessage(sender, "script.error.not_found", args = listOf(script.wrap()))
            return Command.SINGLE_SUCCESS
        }
        LangManager.sendMessage(sender, "script.check.one_started", args = listOf(script.wrap()))
        Root.launch {
            DataManager.checkAsync(Resource.SCRIPTS.child(script), sender)
        }
        return Command.SINGLE_SUCCESS
    }

    fun reloadConfig(context: CommandContext<CommandSourceStack>): Int {
        ReloadManager.reload(context.source.sender, false)
        return Command.SINGLE_SUCCESS
    }

    fun clearCache(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        if (DataManager.isActive()) {
            LangManager.sendMessage(sender, "script.operation.busy")
            return Command.SINGLE_SUCCESS
        }
        ScriptImportCache.reset()
        LangManager.sendMessage(sender, "cache.cleared")
        return Command.SINGLE_SUCCESS
    }
}
