package eternalScript.core.script.definition

import eternalScript.core.data.Resource
import java.security.MessageDigest
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache

private fun compiledScriptUniqueName(
    script: SourceCode,
    scriptCompilationConfiguration: ScriptCompilationConfiguration
): String {
    val digest = MessageDigest.getInstance("MD5")
    digest.update(ScriptImportCache.generation().toByteArray())
    digest.update(script.text.toByteArray())
    ScriptImportCache.fingerprint(script)?.let { fingerprint ->
        digest.update(fingerprint.toByteArray())
    }
    scriptCompilationConfiguration.notTransientData.entries
        .sortedBy { it.key.name }
        .forEach { entry ->
            digest.update(entry.key.name.toByteArray())
            digest.update(entry.value.toString().toByteArray())
        }
    return digest.digest().toHexString()
}

private fun cache(): CompiledScriptJarsCache {
    ScriptImportCache.prepare()
    return CompiledScriptJarsCache { script, scriptCompilationConfiguration ->
        val name = compiledScriptUniqueName(script, scriptCompilationConfiguration)
        Resource.CACHE.child("$name.jar")
    }
}

object ScriptingHostConfig : ScriptingHostConfiguration({
    jvm {
        compilationCache(cache())
    }
})
