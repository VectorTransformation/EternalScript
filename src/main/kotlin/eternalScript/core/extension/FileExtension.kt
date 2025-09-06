package eternalScript.core.extension

import java.io.File

fun File.child(child: String) = File(this, child)

fun File.make() = apply {
    parentFile?.mkdirs()
    if (extension.isEmpty()) mkdir() else createNewFile()
}

fun File.save(content: String) = save(content.toByteArray())

fun File.save(content: ByteArray) {
    make()
    writeBytes(content)
}

fun File.searchSequence(
    fileFilter: (File) -> Boolean = { true }
): Sequence<File> = sequence {
    listFiles()?.forEach { file ->
        if (!file.isDirectory && fileFilter(file)) {
            yield(file)
        }
    }
}

fun File.searchAllSequence(
    fileFilter: (File) -> Boolean = { true },
    directoryFilter: (File) -> Boolean = { true }
): Sequence<File> = sequence {
    val stack = ArrayDeque<File>()
    stack.add(this@searchAllSequence)

    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        current.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                if (directoryFilter(file)) {
                    stack.add(file)
                }
            } else {
                if (fileFilter(file)) {
                    yield(file)
                }
            }
        }
    }
}

fun File.clear() {
    deleteRecursively()
}