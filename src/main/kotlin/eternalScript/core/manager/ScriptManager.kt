package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.core.data.Config
import eternalScript.core.script.data.ScriptLifecycle
import eternalScript.core.extension.unwrap
import eternalScript.core.extension.wrap
import eternalScript.core.script.Script
import eternalScript.core.script.data.ScriptData
import eternalScript.core.script.data.ScriptFile
import eternalScript.core.script.definition.ScriptCompilerConfig
import eternalScript.core.script.definition.ScriptEvaluatorConfig
import eternalScript.core.script.definition.ScriptingHostConfig
import eternalScript.core.the.Root
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.bukkit.command.CommandSender
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

object ScriptManager : Manager {
    private val evaluatorConfigCache = ConcurrentHashMap<String, ScriptEvaluationConfiguration>()
    private val scriptingHost = BasicJvmScriptingHost(ScriptingHostConfig)
    private val cache = ConcurrentHashMap<String, ScriptData>()

    override fun unregister() {
        clear()
    }

    private fun eval(scriptFile: ScriptFile): ResultWithDiagnostics<EvaluationResult> {
        return scriptingHost.eval(scriptFile.source, ScriptCompilerConfig, evaluatorConfig())
    }

    fun load(scriptFile: ScriptFile, sender: CommandSender? = null, isCompile: Boolean = false) {
        val unwrap = scriptFile.name.unwrap()

        val result = eval(scriptFile)

        result.reports.forEach { report ->
            when (report.severity) {
                ScriptDiagnostic.Severity.DEBUG,
                ScriptDiagnostic.Severity.WARNING -> {}
                else -> {
                    val severity = report.severity.let { " | Severity: ${it.name}" }
                    val line = report.location?.start?.line?.let { " | Line: $it" }.orEmpty()
                    val message = report.message.let { " | Message: $it" }
                    val exception = report.exception?.let { " | Exception: ${it.message}" }.orEmpty()
                    val result = "${scriptFile.name}$severity$line$message$exception"
                    Root.sendInfo(sender, result)
                }
            }
        }

        val scriptInstance = result.valueOrNull()?.returnValue?.scriptInstance as? Script ?: return
        val scriptData = ScriptData(scriptInstance)

        remove(unwrap, silent = true)

        cache[unwrap] = scriptData

        if (ConfigManager.value(Config.DEBUG)) {
            if (!isCompile) {
                LangManager.sendMessage(sender, "script.loaded")
            }
            LangManager.sendMessage(sender, "script.format", args = listOf(unwrap.wrap()))
        }

        scriptData.script.functionManager.call(scriptData.script, ScriptLifecycle.ENABLE)
    }

    fun clear(sender: CommandSender? = null, silent: Boolean = false) {
        Root.launch {
            val keys = cache.keys
            if (keys.isEmpty()) return@launch
            if (!silent) {
                if (ConfigManager.value(Config.DEBUG)) {
                    LangManager.sendMessage(sender, "script.unloaded")
                }
            }
            keys.forEach { key ->
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

        cache[unwrap]?.script?.let { script ->
            script.functionManager.call(script, ScriptLifecycle.DISABLE)
        }

        cache.remove(unwrap)

        if (silent) return

        if (ConfigManager.value(Config.DEBUG)) {
            if (!isClear) {
                LangManager.sendMessage(sender, "script.unloaded")
            }
            LangManager.sendMessage(sender, "script.format", args = listOf(unwrap.wrap()))
        }
    }

    fun scripts() = cache.keys

    fun script(script: String) = cache[script]

    fun functions(script: String) = cache[script]?.scriptParser?.functionCache?.filterValues { it.parameters.size == 1 }?.keys ?: emptyList()

    fun call(script: String, function: String, vararg args: Any?) = script(script.unwrap())?.let { data ->
        data.scriptParser.call(data.script, function.unwrap(), *args)
    }

    fun scriptList(sender: CommandSender? = null) {
        LangManager.sendMessage(sender, "script.list")
        scripts().map(String::wrap).forEach { script ->
            LangManager.sendMessage(sender, "script.format", args = listOf(script))
        }
    }

    fun evaluatorConfig(): ScriptEvaluationConfiguration {
        val classLoader = ConfigManager.value<String>(Config.CLASS_LOADER)
        return evaluatorConfigCache.getOrPut(classLoader) {
            ScriptEvaluatorConfig()
        }
    }
}

