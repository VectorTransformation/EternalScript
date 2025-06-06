package eternalScript.manager

import eternalScript.data.Lifecycle
import eternalScript.data.Resource
import eternalScript.definition.Script
import eternalScript.definition.ScriptParser
import eternalScript.extension.toComponent
import eternalScript.extension.unwrap
import eternalScript.extension.wrap
import eternalScript.the.Root
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate

object ScriptManager {
    private val compilerConfig = createJvmCompilationConfigurationFromTemplate<Script>()
    private val evaluatorConfig = createJvmEvaluationConfigurationFromTemplate<Script>()
    private val compiler = BasicJvmScriptingHost()
    private val cache = ConcurrentHashMap<String, Pair<Script, ScriptParser>>()

    private fun eval(value: String) = compiler.eval(value.toScriptSource(), compilerConfig, evaluatorConfig)

    fun load(script: String, value: String, sender: CommandSender? = null) {
        val unwrap = script.unwrap()
        remove(unwrap, sender)

        val result = eval(value)

        result.reports.forEach { report ->
            if (report.severity > ScriptDiagnostic.Severity.WARNING) {
                val line = report.location?.start?.line
                val message = report.message
                val exception = report.exception?.let { " | Exception: ${it.message}" }.orEmpty()
                val result = "${unwrap.wrap()} | Line: $line | Message: $message".plus(exception)
                Root.warn(result.toComponent())
                if (sender is Player) {
                    Root.sendWarn(sender, result)
                }
            }
        }

        val returnValue = result.valueOrNull()?.returnValue?.scriptInstance as? Script ?: return

        cache[unwrap] = returnValue to ScriptParser(returnValue::class)

        returnValue.call(Lifecycle.ENABLE.function)

        val debug = DataManager.config<Boolean>(Resource.CONFIG, "debug")
        if (debug == true) {
            val result = "Loaded Script [${unwrap.wrap()}]"
            if (sender is Player) {
                Root.sendWarn(sender, result)
            } else {
                Root.info(result.toComponent())
            }
        }
    }

    fun clear(sender: CommandSender? = null) {
        cache.keys.forEach { key ->
            remove(key, sender)
        }
    }

    fun remove(key: String, sender: CommandSender? = null) {
        val unwrap = key.unwrap()
        val script = cache[unwrap]?.first ?: return

        script.call(Lifecycle.DISABLE.function)

        val debug = DataManager.config<Boolean>(Resource.CONFIG, "debug")
        if (debug == true) {
            val result = "Unloaded Script [${unwrap.wrap()}]"
            if (sender is Player) {
                Root.sendWarn(sender, result)
            } else {
                Root.info(result.toComponent())
            }
        }

        cache.remove(unwrap)
    }

    fun scripts() = cache.keys

    fun script(script: String) = cache[script]

    fun functions(script: String) = cache[script]?.second?.functionCache?.filterValues { it.parameters.size == 1 }?.keys ?: emptyList()

    fun call(script: String, function: String, vararg args: Any?) = script(script.unwrap())?.let {
        it.second.call(it.first, function.unwrap(), *args)
    }
}

