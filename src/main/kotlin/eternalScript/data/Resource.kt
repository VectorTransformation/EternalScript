package eternalScript.data

import eternalScript.extension.*
import eternalScript.the.Root
import java.io.File

private val dataFolder = Root.dataFolder()

enum class Resource(val file: File) {
    DATA_FOLDER(dataFolder),
    SCRIPTS(dataFolder.child("scripts")),
    CONFIG(dataFolder.child("config.yml")),
    LIBS(dataFolder.child("libs")),
    ;

    fun path() = file.invariantSeparatorsPath

    fun child(child: String) = file.child(child)

    fun make() = file.make()

    fun save(content: String) = file.save(content)

    fun save(content: ByteArray) = file.save(content)

    fun searchSequence(
        fileFilter: (File) -> Boolean = { true }
    ) = file.searchSequence(fileFilter)

    fun searchAllSequence(
        fileFilter: (File) -> Boolean = { true },
        directoryFilter: (File) -> Boolean = { true }
    ) = file.searchAllSequence(fileFilter, directoryFilter)

    fun searchFlow(
        fileFilter: (File) -> Boolean = { true }
    ) = file.searchFlow(fileFilter)

    fun searchAllFlow(
        fileFilter: (File) -> Boolean = { true },
        directoryFilter: (File) -> Boolean = { true }
    ) = file.searchAllFlow(fileFilter, directoryFilter)

    fun clear() = file.clear()

    fun exists() = file.exists()
}