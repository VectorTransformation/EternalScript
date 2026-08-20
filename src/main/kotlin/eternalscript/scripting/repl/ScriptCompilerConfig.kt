package eternalscript.scripting.repl

import eternalscript.api.script.Script
import eternalscript.scripting.source.ETERNAL_SCRIPT_EXTENSION
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.fileExtension
import kotlin.script.experimental.jvm.jvm

internal const val SCRIPT_JVM_TARGET = "25"

internal val SCRIPT_DEFAULT_IMPORTS: List<String> = listOf(
    "org.bukkit.Bukkit",
    "org.bukkit.event.player.PlayerJoinEvent",
    "org.bukkit.event.player.PlayerQuitEvent",
    "eternalscript.api.script.feedback.ScriptFeedbackLevel",
    "eternalscript.api.script.feedback.ScriptFeedbackMessage"
)

/**
 * Static script-definition defaults only. Runtime classpaths are applied by
 * [ScriptCompilationEnvironmentFactory] from a main-thread environment snapshot.
 */
internal object ScriptCompilerConfig : ScriptCompilationConfiguration({
    baseClass(Script::class)
    fileExtension(ETERNAL_SCRIPT_EXTENSION)
    defaultImports.append(SCRIPT_DEFAULT_IMPORTS)

    jvm {
        compilerOptions.append("-jvm-target=$SCRIPT_JVM_TARGET")
    }
})
