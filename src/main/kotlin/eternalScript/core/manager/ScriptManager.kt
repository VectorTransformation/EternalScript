package eternalScript.core.manager

import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.data.ScriptLifecycle
import eternalScript.core.definition.ScriptDefinition
import eternalScript.core.dialog.CustomDialog
import eternalScript.core.extension.toComponent
import eternalScript.core.extension.toTranslatable
import eternalScript.core.extension.unwrap
import eternalScript.core.extension.wrap
import eternalScript.core.script.Script
import eternalScript.core.script.ScriptData
import eternalScript.core.script.ScriptParser
import eternalScript.core.the.Root
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.registry.set.RegistrySet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate

object ScriptManager {
    private val compilerConfig = createJvmCompilationConfigurationFromTemplate<ScriptDefinition>()
    private val evaluatorConfigCache = ConcurrentHashMap<String, ScriptEvaluationConfiguration>()
    private val compiler = BasicJvmScriptingHost()
    private val cache = ConcurrentHashMap<String, ScriptData>()

    private fun eval(value: String): ResultWithDiagnostics<EvaluationResult> {
        val script = DataManager.utils() + value
        return compiler.eval(script.toScriptSource(), compilerConfig, evaluatorConfig())
    }

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

        cache[unwrap] = ScriptData(returnValue, ScriptParser(returnValue::class))

        if (ConfigManager.value(Config.DEBUG)) {
            if (!isCompile) {
                LangManager.sendMessage(sender, "script.loaded")
            }
            LangManager.sendMessage(sender, "script.format", args = listOf(unwrap.wrap()))
        }

        returnValue.call(ScriptLifecycle.ENABLE)
    }

    fun clear(sender: CommandSender? = null, silent: Boolean = false) {
        Root.scope.launch {
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

        cache[unwrap]?.script?.call(ScriptLifecycle.DISABLE)

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

    fun showScriptList(player: Player) {
        val scripts = scripts().mapNotNull(::scriptView).map(CustomDialog::dialog)
        CustomDialog.builder {
            type = DialogType.dialogList(RegistrySet.valueSet(RegistryKey.DIALOG, scripts))
                .columns(1)
                .buttonWidth(256)
                .build()
            title = LangManager.translatable("script.list").toComponent()
        }.show(player)
    }

    fun scriptView(script: String): CustomDialog? {
        val file = Resource.PLUGINS.child(script)
        if (!file.exists()) return null
        val text = file.readText()
        return CustomDialog.builder {
            type = DialogType.notice(
                ActionButton.builder("gui.back".toTranslatable())
                    .action(DialogAction.customClick({ _, audience ->
                        if (audience is Player) {
                            showScriptList(audience)
                        }
                    }, ClickCallback.Options.builder().build())).build()
            )
            title = script.toComponent()
            body =
                listOf(DialogBody.plainMessage(text.toComponent().clickEvent(ClickEvent.copyToClipboard(text)), 1024))
            afterAction = DialogBase.DialogAfterAction.NONE
        }
    }

    fun scriptView(player: Player, script: String) {
        scriptView(script)?.show(player)
    }

    fun evaluatorConfig(): ScriptEvaluationConfiguration {
        val classLoader = ConfigManager.value<String>(Config.CLASS_LOADER)
        return evaluatorConfigCache.getOrPut(classLoader) {
            createJvmEvaluationConfigurationFromTemplate<ScriptDefinition>()
        }
    }
}

