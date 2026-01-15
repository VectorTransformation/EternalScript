package eternalScript.core.script.definition

import eternalScript.core.data.Resource
import eternalScript.core.script.data.ScriptSuffix
import eternalScript.core.extension.*
import java.io.File
import java.security.MessageDigest
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache

private val regex = """@file:Import\s*\(\s*"([^"]+)"(?:\s*,\s*"([^"]+)")*\s*\)""".toRegex()

private fun scriptHash(text: String): String {
    val md = MessageDigest.getInstance("SHA-256")

    md.update(text.toByteArray())

    val imports = regex.findAll(text)
        .flatMap { it.groupValues.drop(1) }
        .filter { it.isNotBlank() }

    for (path in imports) {
        val resource = Resource.PLUGINS.child(path)

        if (!resource.exists()) continue

        if (resource.isDirectory) {
            resource.searchAllSequence(
                { file ->
                    if (!ScriptSuffix.SCRIPT.check(file)) return@searchAllSequence false
                    true
                }
            ).forEach { file ->
                val text = file.readText()
                md.update(text.toByteArray())
            }
        } else {
            val text = resource.readText()
            md.update(text.toByteArray())
        }
    }

    return md.digest().toHexString()
}

private fun cache() = CompiledScriptJarsCache { code, _ ->
    val name = code.name?.toSHA256() ?: return@CompiledScriptJarsCache null
    val hash = scriptHash(code.text)

    val cacheDirectory = Resource.CACHE.child(name).make()

    cacheDirectory.searchAllSequence(
        { file ->
            file.extension == "jar" && file.nameWithoutExtension != hash
        }
    ).forEach(File::clear)

    cacheDirectory.child("$hash.jar")
}

object ScriptingHostConfig : ScriptingHostConfiguration({
    jvm {
        compilationCache(cache())
    }
})