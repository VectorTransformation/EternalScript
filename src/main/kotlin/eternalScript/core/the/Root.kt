package eternalScript.core.the

import eternalScript.api.manager.Manager
import eternalScript.EternalScript
import eternalScript.api.command.CommandBuilder
import eternalScript.core.extension.toComponent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
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

    private fun registerEvents(listener: Listener) = pluginManager().registerEvents(listener, instance())

    fun register(vararg listener: Listener) = listener.forEach(::registerEvents)

    fun unregister(vararg listener: Listener) = listener.forEach(HandlerList::unregisterAll)

    fun lifecycleManager() = instance().lifecycleManager

    private fun registerEventHandler(commandBuilder: CommandBuilder) = lifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS) { handler ->
        handler.registrar().register(commandBuilder.builder.build(), commandBuilder.description, commandBuilder.aliases)
    }

    fun register(vararg commandBuilder: CommandBuilder) = commandBuilder.forEach(::registerEventHandler)

    fun register(vararg manager: Manager) = manager.forEach(Manager::register)

    inline fun <reified T : Event> register(
        event: Class<T>,
        listener: Listener,
        priority: EventPriority = EventPriority.NORMAL,
        crossinline block: T.() -> Unit
    ) = pluginManager().registerEvent(
        event,
        listener,
        priority,
        { _, executor -> (executor as T).block() },
        instance()
    )

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

    fun info(message: String, origin: Boolean = true) {
        if (origin) {
            componentLogger().info(message.toComponent())
        } else {
            Bukkit.getLogger().info(message)
        }
    }

    fun sendInfo(sender: CommandSender?, message: String, origin: Boolean = true) {
        if (sender is Player) {
            send(sender, message, origin)
        } else {
            info(message, origin)
        }
    }

    fun scheduler() = Bukkit.getScheduler()

    fun runTask(task: Consumer<BukkitTask>) = scheduler().runTask(instance(), task)

    fun onlinePlayers() = Bukkit.getOnlinePlayers()

    fun classLoader(plugin: String) = pluginManager().getPlugin(plugin)?.javaClass?.classLoader

    val scope = CoroutineScope(Dispatchers.Default)

    val semaphore = Semaphore(20)
}