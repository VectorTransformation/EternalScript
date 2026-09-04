package eternalscript.scripting.repl

import eternalscript.api.script.Script
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.scripting.source.ETERNAL_SCRIPT_EXTENSION
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.fileExtension
import kotlin.script.experimental.jvm.jvm

internal const val SCRIPT_JVM_TARGET = IdeProtocol.SCRIPT_JVM_TARGET

/**
 * Static script-definition defaults only. Runtime classpaths are applied by
 * [ScriptCompilationEnvironmentFactory] from a main-thread environment snapshot.
 */
internal object ScriptCompilerConfig : ScriptCompilationConfiguration({
    baseClass(Script::class)
    fileExtension(ETERNAL_SCRIPT_EXTENSION)

    jvm {
        compilerOptions.append("-jvm-target=$SCRIPT_JVM_TARGET")
    }
})
