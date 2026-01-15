package eternalScript.core.the

import eternalScript.EternalScript
import eternalScript.api.command.CommandBuilder
import eternalScript.api.manager.Manager
import eternalScript.core.extension.tag
import eternalScript.core.extension.toComponent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

object Root {
    const val ORIGIN = "EternalScript"

    val INSTANCE = pluginManager().getPlugin(ORIGIN) as EternalScript

    fun pluginManager() = Bukkit.getPluginManager()

    fun plugins() = pluginManager().plugins

    // event

    inline fun <reified T : Event> register(
        event: KClass<T>,
        listener: Listener,
        priority: EventPriority = EventPriority.NORMAL,
        noinline block: (T) -> Unit
    ) = pluginManager().registerEvent(
        event.java,
        listener,
        priority,
        { _, executor -> if (executor is T) block(executor) },
        INSTANCE
    )

    fun unregister(vararg listener: Listener) = listener.forEach(HandlerList::unregisterAll)

    // command

    fun lifecycleManager() = INSTANCE.lifecycleManager

    private fun registerEventHandler(commandBuilder: CommandBuilder) = lifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS) { handler ->
        handler.registrar().register(commandBuilder.builder.build(), commandBuilder.description, commandBuilder.aliases)
    }

    // util

    fun register(vararg commandBuilder: CommandBuilder) = commandBuilder.forEach(::registerEventHandler)

    fun register(vararg manager: Manager) = manager.forEach(Manager::register)

    fun unregister(vararg manager: Manager) = manager.forEach(Manager::unregister)

    fun dataFolder() = INSTANCE.dataFolder

    fun componentLogger() = INSTANCE.componentLogger

    fun namespace() = "<gray>[${ORIGIN.tag("gold")}]</gray>"

    fun send(sender: CommandSender?, message: String) {
        if (sender !is Player) return
        sender.sendMessage("${namespace()} $message".toComponent())
    }

    fun info(message: String) {
        componentLogger().info(message.toComponent())
    }

    fun sendInfo(sender: CommandSender?, message: String) {
        send(sender, message)
        componentLogger().info(message)
    }

    fun onlinePlayers() = Bukkit.getOnlinePlayers()

    fun classLoader(plugin: String) = pluginManager().getPlugin(plugin)?.javaClass?.classLoader

    private val scope = CoroutineScope(Dispatchers.Default)

    val semaphore = Semaphore(20)

    fun launch(
        context: CoroutineContext = Dispatchers.Default,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ) = scope.launch(context, start, block)

    fun <T> async(
        context: CoroutineContext = Dispatchers.Default,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T
    ) = scope.async(context, start, block)

    suspend fun <T> ioContext(
        block: suspend CoroutineScope.() -> T
    ) = withContext(Dispatchers.IO, block)
}