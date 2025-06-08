package eternalScript.the

import eternalScript.EternalScript
import eternalScript.command.CommandBuilder
import eternalScript.extension.toComponent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitTask
import java.util.function.Consumer

object Root {
    const val ORIGIN = "EternalScript"

    fun instance() = pluginManager().getPlugin(ORIGIN) as EternalScript

    fun pluginManager() = Bukkit.getPluginManager()

    fun <T : Event> register(event: Class<T>, listener: Listener, priority: EventPriority = EventPriority.NORMAL, block: (T) -> Unit) = pluginManager().registerEvent(
        event,
        listener,
        priority,
        { _, executor -> block(executor as T) },
        instance()
    )

    private fun registerEvents(listener: Listener) = pluginManager().registerEvents(listener, instance())

    fun registers(vararg listeners: Listener) = listeners.forEach(::registerEvents)

    fun unregisters(vararg listeners: Listener) = listeners.forEach(HandlerList::unregisterAll)

    fun lifecycleManager() = instance().lifecycleManager

    private fun registerEventHandler(commandBuilder: CommandBuilder) = lifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS) { handler ->
        handler.registrar().register(commandBuilder.builder.build(), commandBuilder.description, commandBuilder.aliases)
    }

    fun registers(vararg commandBuilders: CommandBuilder) = commandBuilders.forEach(::registerEventHandler)

    fun dataFolder() = instance().dataFolder

    fun componentLogger() = instance().componentLogger

    fun send(sender: Player, message: String, origin: Boolean = true) {
        val hex = "<#EC9800>"
        if (origin) {
            sender.sendMessage("$hex[$ORIGIN] $message".toComponent())
        } else {
            sender.sendMessage("$hex$message".toComponent())
        }
    }

    fun info(component: Component) {
        componentLogger().info(component)
    }

    fun sendInfo(sender: CommandSender?, message: String, origin: Boolean = true) {
        if (sender is Player) {
            send(sender, message, origin)
        } else {
            info(message.toComponent())
        }
    }

    fun scheduler() = Bukkit.getScheduler()

    fun runTask(task: Consumer<BukkitTask>) = scheduler().runTask(instance(), task)

    fun onlinePlayers() = Bukkit.getOnlinePlayers()

    val scope = CoroutineScope(Dispatchers.Default)

    val semaphore = Semaphore(20)
}