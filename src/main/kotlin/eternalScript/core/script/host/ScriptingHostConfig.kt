package eternalScript.core.script.host

import eternalScript.core.data.Resource
import eternalScript.core.extension.*
import java.io.File
import java.security.MessageDigest
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache

private val regex = """@file:Import\s*\(\s*"([^"]+)"\s*(?:,\s*"([^"]+)"\s*)*\)""".toRegex()

private fun MessageDigest.scriptHashRecursively(text: String, ignored: MutableSet<String>) {
    val imports = regex.findAll(text)
        .flatMap { match ->
            match.groupValues.drop(1)
        }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    for (importPath in imports) {
        val file = Resource.PLUGINS.child(importPath)
        val absolutePath = file.absolutePath

        if (!file.exists() || !ignored.add(absolutePath)) continue

        val fileText = file.readText()

        update(fileText.toByteArray())

        scriptHashRecursively(fileText, ignored)
    }
}

private fun scriptHash(text: String): String {
    val md = MessageDigest.getInstance("MD5")

    md.update(text.toByteArray())

    md.scriptHashRecursively(text, mutableSetOf())

    return md.digest().toHexString()
}

private fun cache() = CompiledScriptJarsCache { code, configuration ->
    val name = code.name?.toMd5() ?: return@CompiledScriptJarsCache null
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