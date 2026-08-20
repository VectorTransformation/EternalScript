@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package eternalscript.scripting.repl.k2

import eternalscript.api.script.Script
import eternalscript.scripting.repl.ScriptingHostConfig
import eternalscript.scripting.runtime.ReplStateBridge
import org.jetbrains.kotlin.scripting.compiler.plugin.services.FirReplHistoryProviderImpl
import org.jetbrains.kotlin.scripting.compiler.plugin.services.firReplHistoryProvider
import org.jetbrains.kotlin.scripting.compiler.plugin.services.isReplSnippetSource
import org.jetbrains.kotlin.scripting.compiler.plugin.services.replStateObjectFqName
import kotlin.script.experimental.api.ReplScriptingHostConfigurationKeys
import kotlin.script.experimental.api.repl
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.util.PropertiesCollection
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm

internal const val K2_REPL_COMPILER_ABI: String = "2.4.10-eternalscript-batch-6"

internal data class ProviderSnippetDescriptor(
    val path: String,
    val className: String,
    val stateKey: String
)

internal val ReplScriptingHostConfigurationKeys.providerSnippets
    by PropertiesCollection.key<List<ProviderSnippetDescriptor>>(isTransient = true)

internal fun batchScriptingHostConfiguration(
    providers: List<ProviderSnippetDescriptor> = emptyList(),
    baseClassLoader: ClassLoader = Script::class.java.classLoader
): ScriptingHostConfiguration = ScriptingHostConfiguration(
    ScriptingHostConfig
) {
        repl {
            firReplHistoryProvider(FirReplHistoryProviderImpl())
            isReplSnippetSource { _, _ -> true }
            replStateObjectFqName(ReplStateBridge::class.qualifiedName!!)
            providerSnippets(providers)
        }
        jvm {
            baseClassLoader(baseClassLoader)
        }
    }
