package eternalScript.core.runtime

import eternalScript.core.command.CommandBuilder
import eternalScript.core.script.command.ScriptCommand
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.util.Locale
import kotlin.reflect.KClass

/** Bukkit/Paper integration boundary used by the plugin runtime. */
internal class ServerAccess(
    private val host: PluginHost
) {
    private val commandMap
        get() = host.plugin.server.commandMap

    val plugins: List<Plugin>
        get() = Bukkit.getPluginManager().plugins.toList()

    val isGlobalTickThread: Boolean
        get() = Bukkit.isGlobalTickThread()

    fun <T : Event> registerEvent(
        event: KClass<T>,
        listener: Listener,
        priority: EventPriority = EventPriority.NORMAL,
        block: (T) -> Unit
    ) {
        Bukkit.getPluginManager().registerEvent(
            event.java,
            listener,
            priority,
            { _, value ->
                if (event.java.isInstance(value)) {
                    block(event.java.cast(value))
                }
            },
            host.plugin
        )
    }

    fun unregister(listener: Listener) {
        HandlerList.unregisterAll(listener)
    }

    fun registerCommand(builder: CommandBuilder) {
        host.plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { handler ->
            handler.registrar().register(
                builder.builder.build(),
                builder.description,
                builder.aliases
            )
        }
    }

    fun command(name: String): Command? = commandMap.getCommand(name)

    fun registerScriptCommand(command: Command): Boolean =
        commandMap.register(command.name, PLUGIN_NAME.lowercase(Locale.ROOT), command)

    fun removeScriptCommand(command: Command) {
        scriptCommandKeys(command.name, command.aliases).forEach { key ->
            if (commandMap.knownCommands[key] === command) {
                commandMap.knownCommands.remove(key)
            }
        }
        command.unregister(commandMap)
    }

    fun scriptCommandKeys(name: String, aliases: List<String>): List<String> =
        (listOf(name) + aliases).flatMap { key ->
            listOf(key, "${PLUGIN_NAME.lowercase(Locale.ROOT)}:$key")
        }

    fun updateOnlineCommands() {
        Bukkit.getOnlinePlayers().forEach { player ->
            player.scheduler.run(
                host.plugin,
                { player.updateCommands() },
                null
            )
        }
    }

    fun classLoader(pluginName: String): ClassLoader? =
        Bukkit.getPluginManager().getPlugin(pluginName)?.javaClass?.classLoader

    fun executeGlobal(block: () -> Unit) {
        host.plugin.server.globalRegionScheduler.execute(host.plugin, block)
    }

    fun runGlobalDelayed(delayTicks: Long, block: () -> Unit) {
        host.plugin.server.globalRegionScheduler.runDelayed(
            host.plugin,
            { block() },
            delayTicks
        )
    }
}
