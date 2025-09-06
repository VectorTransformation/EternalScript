package eternalScript.core.manager

import eternalScript.core.data.Config
import eternalScript.core.data.ScriptLifecycle
import eternalScript.core.definition.Script
import eternalScript.core.definition.ScriptParser
import eternalScript.core.extension.unwrap
import eternalScript.core.extension.wrap
import eternalScript.core.the.Root
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.bukkit.command.CommandSender
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate

object ScriptManager {
    private val compilerConfig = createJvmCompilationConfigurationFromTemplate<Script>()
    private val evaluatorConfigCache = ConcurrentHashMap<String, ScriptEvaluationConfiguration>()
    private val compiler = BasicJvmScriptingHost()
    private val cache = ConcurrentHashMap<String, Pair<Script, ScriptParser>>()

    private fun eval(value: String) = compiler.eval(value.toScriptSource(), compilerConfig, evaluatorConfig())

    fun load(script: String, value: String, sender: CommandSender? = null, isCompile: Boolean = false) {
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

        if (ConfigManager.value(Config.DEBUG)) {
            if (!isCompile) {
                val result = "Loaded Script"
                Root.sendInfo(sender, result)
            }
            val script = "- ${unwrap.wrap()}"
            Root.sendInfo(sender,  script, false)
        }

        returnValue.call(ScriptLifecycle.ENABLE.function)
    }

    fun clear(sender: CommandSender? = null, silent: Boolean = false) {
        Root.scope.launch {
            if (!silent) {
                if (ConfigManager.value(Config.DEBUG)) {
                    val result = "Unloaded Script"
                    Root.sendInfo(sender, result)
                }
            }
            cache.keys.forEach { key ->
                launch {
                    Root.semaphore.withPermit {
                        remove(key, sender, silent, true)
                    }
                }
            }
        }
    }

    fun remove(key: String, sender: CommandSender? = null, silent: Boolean = false, isClear: Boolean = false) {
        val unwrap = key.unwrap()

        cache[unwrap]?.first?.call(ScriptLifecycle.DISABLE.function)

        cache.remove(unwrap)

        if (silent) return

        if (ConfigManager.value(Config.DEBUG)) {
            if (!isClear) {
                val result = "Unloaded Script"
                Root.sendInfo(sender, result)
            }
            val script = "- ${unwrap.wrap()}"
            Root.sendInfo(sender,  script, false)
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

    fun evaluatorConfig(): ScriptEvaluationConfiguration {
        val classLoader = ConfigManager.value<String>(Config.CLASS_LOADER)
        return evaluatorConfigCache.getOrPut(classLoader) {
            createJvmEvaluationConfigurationFromTemplate<Script>()
        }
    }
}

