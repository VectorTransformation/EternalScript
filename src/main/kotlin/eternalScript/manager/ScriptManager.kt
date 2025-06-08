package eternalScript.manager

import eternalScript.data.Lifecycle
import eternalScript.data.Resource
import eternalScript.definition.Script
import eternalScript.definition.ScriptParser
import eternalScript.extension.unwrap
import eternalScript.extension.wrap
import eternalScript.the.Root
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.bukkit.command.CommandSender
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

        val result = eval(value)

        result.reports.forEach { report ->
            if (report.severity > ScriptDiagnostic.Severity.WARNING) {
                val line = report.location?.start?.line
                val message = report.message
                val exception = report.exception?.let { " | Exception: ${it.message}" }.orEmpty()
                val result = "${unwrap.wrap()} | Line: $line | Message: $message".plus(exception)
                Root.sendInfo(sender, result)
            }
        }

        val returnValue = result.valueOrNull()?.returnValue?.scriptInstance as? Script ?: return

        remove(unwrap, silent = true)

        cache[unwrap] = returnValue to ScriptParser(returnValue::class)

        returnValue.call(Lifecycle.ENABLE.function)

        val debug = DataManager.config<Boolean>(Resource.CONFIG, "debug")
        if (debug == true) {
            val result = "Loaded Script - ${unwrap.wrap()}"
            Root.sendInfo(sender, result)
        }
    }

    fun clear(sender: CommandSender? = null) {
        Root.scope.launch {
            cache.keys.forEach { key ->
                launch {
                    Root.semaphore.withPermit {
                        remove(key, sender)
                    }
                }
            }
        }
    }

    fun remove(key: String, sender: CommandSender? = null, silent: Boolean = false) {
        val unwrap = key.unwrap()

        cache[unwrap]?.first?.call(Lifecycle.DISABLE.function)

        cache.remove(unwrap)

        if (silent) return

        val debug = DataManager.config<Boolean>(Resource.CONFIG, "debug")
        if (debug == true) {
            val result = "Unloaded Script - ${unwrap.wrap()}"
            Root.sendInfo(sender, result)
        }
    }

    fun scripts() = cache.keys

    fun script(script: String) = cache[script]

    fun functions(script: String) = cache[script]?.second?.functionCache?.filterValues { it.parameters.size == 1 }?.keys ?: emptyList()

    fun call(script: String, function: String, vararg args: Any?) = script(script.unwrap())?.let {
        it.second.call(it.first, function.unwrap(), *args)
    }

    fun scriptList(sender: CommandSender? = null) {
        val result = "Script List"
        Root.sendInfo(sender, result)
        scripts().map { script ->
            "- ${script.wrap()}"
        }.forEach { script ->
            Root.sendInfo(sender, script, false)
        }
    }
}

