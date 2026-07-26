package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.core.data.Config
import eternalScript.core.extension.unwrap
import eternalScript.core.extension.wrap
import eternalScript.core.script.Script
import eternalScript.core.script.data.ScriptData
import eternalScript.core.script.data.ScriptFile
import eternalScript.core.script.definition.ScriptCompilerConfig
import eternalScript.core.script.definition.ScriptEvaluatorConfig
import eternalScript.core.script.definition.ScriptingHostConfig
import eternalScript.core.the.Root
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.bukkit.command.CommandSender
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

object ScriptManager : Manager {
    private val evaluatorConfigCache = ConcurrentHashMap<String, ScriptEvaluationConfiguration>()
    private val scriptingHost = BasicJvmScriptingHost(ScriptingHostConfig)
    private val cache = ConcurrentHashMap<String, ScriptData>()

    override fun unregister() {
        cache.keys.toList().forEach { key ->
            remove(key, silent = true)
        }
    }

    private fun eval(scriptFile: ScriptFile): ResultWithDiagnostics<EvaluationResult> {
        return scriptingHost.eval(scriptFile.source, ScriptCompilerConfig, evaluatorConfig())
    }

    private fun compile(scriptFile: ScriptFile) = scriptingHost.runInCoroutineContext {
        scriptingHost.compiler(scriptFile.source, ScriptCompilerConfig)
    }

    private fun report(scriptFile: ScriptFile, result: ResultWithDiagnostics<*>, sender: CommandSender?): Int {
        val errors = result.reports
            .filter {
                it.severity == ScriptDiagnostic.Severity.ERROR ||
                    it.severity == ScriptDiagnostic.Severity.FATAL
            }
            .distinctBy {
                "${it.location?.start?.line}:${it.location?.start?.col}:${it.message}"
            }

        errors.forEach { report ->
            LangManager.sendMessage(
                sender,
                "script.compile.error",
                args = listOf(
                    scriptFile.name.wrap(),
                    report.location?.start?.line?.toString() ?: "-",
                    report.location?.start?.col?.toString() ?: "-",
                    report.message
                )
            )
            if (ConfigManager.value(Config.DEBUG)) {
                report.exception?.let { exception ->
                    val message = LangManager.translatable("script.compile.exception")
                        .format(scriptFile.name.wrap())
                    Root.INSTANCE.logger.log(Level.WARNING, message, exception)
                }
            }
        }
        return errors.size
    }

    fun load(scriptFile: ScriptFile, sender: CommandSender? = null): Boolean {
        val unwrap = scriptFile.name.unwrap()

        val result = eval(scriptFile)
        report(scriptFile, result, sender)

        val scriptInstance = result.valueOrNull()?.returnValue?.scriptInstance as? Script ?: return false
        val replacement = ScriptData(scriptInstance)
        val current = cache[unwrap]

        if (current == null) {
            return activateInitial(scriptFile, replacement, sender)
        }

        return replace(scriptFile, unwrap, current, replacement, sender)
    }

    private fun activateInitial(
        scriptFile: ScriptFile,
        scriptData: ScriptData,
        sender: CommandSender?
    ): Boolean {
        return try {
            scriptData.activate()
            cache[scriptFile.name.unwrap()] = scriptData
            true
        } catch (exception: Throwable) {
            lifecycleFailure(scriptFile.name, "enable", exception, sender)
            cleanupFailedActivation(scriptFile, scriptData, sender)
            false
        }
    }

    private fun replace(
        scriptFile: ScriptFile,
        key: String,
        current: ScriptData,
        replacement: ScriptData,
        sender: CommandSender?
    ): Boolean {
        try {
            current.deactivate()
        } catch (exception: Throwable) {
            lifecycleFailure(scriptFile.name, "disable", exception, sender)
            replacement.dispose()
            restore(scriptFile, key, current, sender)
            return false
        }

        try {
            replacement.activate()
        } catch (exception: Throwable) {
            lifecycleFailure(scriptFile.name, "enable", exception, sender)
            cleanupFailedActivation(scriptFile, replacement, sender)
            restore(scriptFile, key, current, sender)
            return false
        }

        cache[key] = replacement
        current.dispose()
        return true
    }

    private fun restore(
        scriptFile: ScriptFile,
        key: String,
        scriptData: ScriptData,
        sender: CommandSender?
    ): Boolean {
        return try {
            scriptData.activate()
            cache[key] = scriptData
            true
        } catch (exception: Throwable) {
            lifecycleFailure(scriptFile.name, "restore", exception, sender)
            scriptData.dispose()
            cache.remove(key, scriptData)
            false
        }
    }

    private fun cleanupFailedActivation(
        scriptFile: ScriptFile,
        scriptData: ScriptData,
        sender: CommandSender?
    ) {
        try {
            scriptData.deactivate()
        } catch (exception: Throwable) {
            lifecycleFailure(scriptFile.name, "cleanup", exception, sender)
        } finally {
            scriptData.dispose()
        }
    }

    private fun lifecycleFailure(
        scriptName: String,
        phase: String,
        exception: Throwable,
        sender: CommandSender?
    ) {
        LangManager.sendMessage(
            sender,
            "script.lifecycle.error",
            args = listOf(
                scriptName.wrap(),
                phase,
                exception.message ?: exception.javaClass.simpleName
            )
        )
        if (ConfigManager.value(Config.DEBUG)) {
            val message = LangManager.translatable("script.lifecycle.exception")
                .format(scriptName.wrap(), phase)
            Root.INSTANCE.logger.log(Level.WARNING, message, exception)
        }
    }

    fun check(scriptFile: ScriptFile, sender: CommandSender? = null, announce: Boolean = true): Boolean {
        val result = compile(scriptFile)
        val errorCount = report(scriptFile, result, sender)
        val success = result.valueOrNull() != null
        if (announce) {
            val key = if (success) "script.check.passed" else "script.check.failed"
            val args = if (success) {
                listOf(scriptFile.name.wrap())
            } else {
                listOf(scriptFile.name.wrap(), errorCount.toString())
            }
            LangManager.sendMessage(sender, key, args = args)
        }
        return success
    }

    fun clear(sender: CommandSender? = null, silent: Boolean = false) {
        Root.launch {
            val count = clearNow()
            if (!silent) {
                LangManager.sendMessage(sender, "script.unload.all_completed", args = listOf(count.toString()))
            }
        }
    }

    suspend fun clearNow(): Int {
        val keys = cache.keys.toList()
        coroutineScope {
            keys.map { key ->
                launch {
                    Root.semaphore.withPermit {
                        runCatching {
                            remove(key, silent = true)
                        }
                    }
                }
            }.joinAll()
        }
        return keys.size
    }

    fun remove(key: String, sender: CommandSender? = null, silent: Boolean = false): Boolean {
        val unwrap = key.unwrap()

        val scriptData = cache[unwrap]
        if (scriptData == null) {
            if (!silent) {
                LangManager.sendMessage(sender, "script.error.not_loaded", args = listOf(unwrap.wrap()))
            }
            return false
        }

        var success = true
        try {
            scriptData.deactivate()
        } catch (exception: Throwable) {
            lifecycleFailure(unwrap, "disable", exception, sender)
            success = false
        } finally {
            scriptData.dispose()
            cache.remove(unwrap, scriptData)
        }

        if (!silent && success) {
            LangManager.sendMessage(sender, "script.unload.completed", args = listOf(unwrap.wrap()))
        }
        return success
    }

    fun scripts() = cache.keys

    fun script(script: String) = cache[script]

    fun functions(script: String) = cache[script]?.scriptParser?.functionCache?.filterValues { it.parameters.size == 1 }?.keys ?: emptyList()

    fun call(script: String, function: String, vararg args: Any?) = script(script.unwrap())?.let { data ->
        data.scriptParser.call(data.script, function.unwrap(), *args)
    }

    fun scriptList(sender: CommandSender? = null) {
        val scripts = scripts().sorted()
        if (scripts.isEmpty()) {
            LangManager.sendMessage(sender, "script.list.empty")
            return
        }
        LangManager.sendMessage(sender, "script.list.header", args = listOf(scripts.size.toString()))
        scripts.map(String::wrap).forEach { script ->
            LangManager.sendMessage(sender, "script.list.entry", args = listOf(script))
        }
    }

    fun evaluatorConfig(): ScriptEvaluationConfiguration {
        val classLoader = ConfigManager.value<String>(Config.CLASS_LOADER)
        return evaluatorConfigCache.getOrPut(classLoader) {
            ScriptEvaluatorConfig()
        }
    }
}

