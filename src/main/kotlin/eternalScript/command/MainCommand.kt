package eternalScript.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eternalScript.data.Resource
import eternalScript.extension.wrap
import eternalScript.manager.ConfigManager
import eternalScript.manager.DataManager
import eternalScript.manager.ScriptManager
import io.papermc.paper.command.brigadier.CommandSourceStack

class MainCommand : CommandBuilder() {
    override val builder = builder("eternalscript") {
        requires(::isOp)
        then(builder("compile") {
            executes(::compile)
        })
        then(builder("clear") {
            executes(::clear)
        })
        then(builder("config") {
            executes(::config)
        })
        then(builder("call") {
            then(builder("script", StringArgumentType.string()) {
                suggests { _, builder ->
                    ScriptManager.scripts().map(String::wrap).filter {
                        it.lowercase().startsWith(builder.remainingLowerCase) &&
                                ScriptManager.functions(it).isNotEmpty()
                    }.forEach {
                        builder.suggest(it)
                    }
                    builder.buildFuture()
                }
                then(builder("function", StringArgumentType.string()) {
                    suggests { context, builder ->
                        val script = StringArgumentType.getString(context, "script")
                        ScriptManager.functions(script).map(String::wrap).filter {
                            it.lowercase().startsWith(builder.remainingLowerCase)
                        }.forEach {
                            builder.suggest(it)
                        }
                        builder.buildFuture()
                    }
                    executes(::call)
                })
            })
        })
        then(builder("load") {
            then(builder("script", StringArgumentType.string()) {
                suggests { _, builder ->
                    DataManager.scripts().map(String::wrap).filter {
                        it.lowercase().startsWith(builder.remainingLowerCase)
                    }.forEach {
                        builder.suggest(it)
                    }
                    builder.buildFuture()
                }
                executes(::load)
            })
        })
        then(builder("unload") {
            then(builder("script", StringArgumentType.string()) {
                suggests { _, builder ->
                    ScriptManager.scripts().map(String::wrap).filter {
                        it.lowercase().startsWith(builder.remainingLowerCase)
                    }.forEach {
                        builder.suggest(it)
                    }
                    builder.buildFuture()
                }
                executes(::unload)
            })
        })
        then(builder("list") {
            executes(::list)
        })
    }
    override val aliases = listOf("es")

    fun compile(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        DataManager.compile(sender)
        return Command.SINGLE_SUCCESS
    }

    fun clear(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        ScriptManager.clear(sender)
        return Command.SINGLE_SUCCESS
    }

    fun config(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        ConfigManager.all(sender, false)
        return Command.SINGLE_SUCCESS
    }

    fun call(context: CommandContext<CommandSourceStack>): Int {
        val script = StringArgumentType.getString(context, "script")
        val function = StringArgumentType.getString(context, "function")
        ScriptManager.call(script, function)
        return Command.SINGLE_SUCCESS
    }

    fun load(context: CommandContext<CommandSourceStack>): Int {
        val script = StringArgumentType.getString(context, "script")
        if (script !in DataManager.scripts()) return Command.SINGLE_SUCCESS
        val value = Resource.PLUGINS.child(script).readText()
        val sender = context.source.sender
        ScriptManager.load(script, value, sender)
        return Command.SINGLE_SUCCESS
    }

    fun unload(context: CommandContext<CommandSourceStack>): Int {
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
}