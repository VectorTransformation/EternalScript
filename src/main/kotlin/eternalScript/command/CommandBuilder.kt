package eternalScript.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands

abstract class CommandBuilder {
    abstract val builder: LiteralArgumentBuilder<CommandSourceStack>
    open val description: String? = null
    open val aliases: Collection<String> = emptyList()

    open fun builder(
        literal: String,
        block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit
    ): LiteralArgumentBuilder<CommandSourceStack> {
        val builder = Commands.literal(literal)
        block.invoke(builder)
        return builder
    }

    open fun builder(
        name: String, argumentType: ArgumentType<out Any>,
        block: RequiredArgumentBuilder<CommandSourceStack, out Any>.() -> Unit
    ): RequiredArgumentBuilder<CommandSourceStack, out Any> {
        val builder = Commands.argument(name, argumentType)
        block.invoke(builder)
        return builder
    }

    fun <T : CommandSourceStack> isOp(context: T) = context.sender.isOp
}