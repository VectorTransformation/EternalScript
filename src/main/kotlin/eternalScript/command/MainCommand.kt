package eternalScript.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eternalScript.data.Resource
import eternalScript.extension.wrap
import eternalScript.manager.DataManager
import eternalScript.manager.ScriptManager
import io.papermc.paper.command.brigadier.CommandSourceStack

class MainCommand : CommandBuilder() {
    override val builder = builder("eternalscript") {
        then(builder("compile") {
            requires(::isOp)
            executes(::compile)
        })
        then(builder("clear") {
            requires(::isOp)
            executes(::clear)
        })
        then(builder("config") {
            requires(::isOp)
            executes(::config)
        })
        then(builder("call") {
            requires(::isOp)
            then(builder("script", StringArgumentType.string()) {
                suggests { _, builder ->
                    ScriptManager.scripts().filter {
                        it.lowercase().startsWith(builder.remainingLowerCase)
                    }.forEach {
                        builder.suggest(it.wrap())
                    }
                    builder.buildFuture()
                }
                then(builder("function", StringArgumentType.string()) {
                    suggests { context, builder ->
                        val script = StringArgumentType.getString(context, "script")
                        ScriptManager.functions(script).filter {
                            it.lowercase().startsWith(builder.remainingLowerCase)
                        }.forEach {
                            builder.suggest(it.wrap())
                        }
                        builder.buildFuture()
                    }
                    executes(::call)
                })
            })
        })
        then(builder("load") {
            requires(::isOp)
            then(builder("script", StringArgumentType.string()) {
                suggests { _, builder ->
                    DataManager.scripts().filter {
                        it.lowercase().startsWith(builder.remainingLowerCase)
                    }.forEach {
                        builder.suggest(it.wrap())
                    }
                    builder.buildFuture()
                }
                executes(::load)
            })
        })
        then(builder("unload") {
            requires(::isOp)
            then(builder("script", StringArgumentType.string()) {
                suggests { _, builder ->
                    ScriptManager.scripts().filter {
                        it.lowercase().startsWith(builder.remainingLowerCase)
                    }.forEach {
                        builder.suggest(it.wrap())
                    }
                    builder.buildFuture()
                }
                executes(::unload)
            })
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
        DataManager.config()
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
        val value = Resource.SCRIPTS.child(script).readText()
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
}