package eternalScript.core.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands

internal abstract class CommandBuilder {
    abstract val builder: LiteralArgumentBuilder<CommandSourceStack>
    open val description: String? = null
    open val aliases: Collection<String> = emptyList()

    fun builder(
        literal: String,
        block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(literal).apply(block)

    fun builder(
        name: String,
        argumentType: ArgumentType<out Any>,
        block: RequiredArgumentBuilder<CommandSourceStack, out Any>.() -> Unit
    ): RequiredArgumentBuilder<CommandSourceStack, out Any> =
        Commands.argument(name, argumentType).apply(block)

    fun <T : CommandSourceStack> isOp(context: T): Boolean = context.sender.isOp
}
