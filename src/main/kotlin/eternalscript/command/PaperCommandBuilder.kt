package eternalscript.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin

internal abstract class PaperCommandBuilder(
    private val plugin: JavaPlugin
) {
    protected abstract val command: LiteralArgumentBuilder<CommandSourceStack>
    protected open val description: String? = null
    protected open val aliases: Collection<String> = emptyList()

    fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(command.build(), description, aliases)
        }
    }

    protected fun literal(
        value: String,
        block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit = {}
    ): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(value).apply(block)

    protected fun argument(
        name: String,
        type: ArgumentType<out Any>,
        block: RequiredArgumentBuilder<CommandSourceStack, out Any>.() -> Unit = {}
    ): RequiredArgumentBuilder<CommandSourceStack, out Any> = Commands.argument(name, type).apply(block)

}
