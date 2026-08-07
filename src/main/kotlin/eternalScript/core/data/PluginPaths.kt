package eternalScript.core.data

import eternalScript.core.extension.child
import eternalScript.core.extension.clear
import eternalScript.core.extension.make
import eternalScript.core.extension.save
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.extension.searchSequence
import java.io.File

internal class PluginPaths(dataFolder: File) {
    val plugins = PluginPath(dataFolder.parentFile)
    val dataFolder = PluginPath(dataFolder)
    val scripts = PluginPath(dataFolder.child("scripts"))
    val config = PluginPath(dataFolder.child("config.yml"))
    val libs = PluginPath(dataFolder.child("libs"))
    val lang = PluginPath(dataFolder.child("lang"))
    val cache = PluginPath(dataFolder.child("cache"))
}

internal class PluginPath(val file: File) {
    fun path() = file.invariantSeparatorsPath

    fun toPath() = file.toPath()

    fun child(child: String) = file.child(child)

    fun make() = file.make()

    fun save(content: String) = file.save(content)

    fun searchSequence(
        fileFilter: (File) -> Boolean = { true }
    ) = file.searchSequence(fileFilter)

    fun searchAllSequence(
        fileFilter: (File) -> Boolean = { true },
        directoryFilter: (File) -> Boolean = { true }
    ) = file.searchAllSequence(fileFilter, directoryFilter)

    fun clear() = file.clear()

    fun exists() = file.exists()
}
