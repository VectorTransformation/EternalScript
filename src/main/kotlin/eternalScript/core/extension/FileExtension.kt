package eternalScript.core.extension

import eternalScript.core.data.Resource
import eternalScript.core.the.Root
import java.io.File
import java.nio.charset.Charset
import kotlin.io.path.invariantSeparatorsPathString

fun File.child(child: String) = File(this, child)

fun File.make() = apply {
    parentFile?.mkdirs()
    if (extension.isEmpty()) mkdir() else createNewFile()
}

fun File.save(text: String) {
    make()
    writeText(text)
}

fun File.searchSequence(
    fileFilter: (File) -> Boolean = { true }
): Sequence<File> {
    val children = listFiles() ?: return emptySequence()
    return children.asSequence().filter(File::isFile).filter(fileFilter)
}

fun File.searchAllSequence(
    fileFilter: (File) -> Boolean = { true },
    directoryFilter: (File) -> Boolean = { true }
): Sequence<File> = sequence {
    val children = listFiles() ?: return@sequence

    for (child in children) {
        when {
            child.isFile -> if (fileFilter(child)) yield(child)
            child.isDirectory -> if (directoryFilter(child)) yieldAll(child.searchAllSequence(fileFilter, directoryFilter))
        }
    }
}

fun File.clear() {
    deleteRecursively()
}

suspend fun File.readTextAsync(
    charset: Charset = Charsets.UTF_8
) = Root.ioContext {
    readText(charset)
}

fun File.relativize(resource: Resource = Resource.PLUGINS) = resource.toPath().relativize(toPath()).invariantSeparatorsPathString