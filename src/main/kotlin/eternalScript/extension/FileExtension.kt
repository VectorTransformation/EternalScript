package eternalScript.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.collections.forEach

fun File.child(child: String) = File(this, child)

fun File.make() {
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

fun File.searchFlow(
    fileFilter: (File) -> Boolean = { true }
): Flow<File> = flow {
    listFiles()?.forEach { file ->
        if (!file.isDirectory && fileFilter(file)) {
            emit(file)
        }
    }
}

fun File.searchAllFlow(
    fileFilter: (File) -> Boolean = { true },
    directoryFilter: (File) -> Boolean = { true }
): Flow<File> = flow {
    val stack = ArrayDeque<File>()
    stack.add(this@searchAllFlow)

    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        current.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                if (directoryFilter(file)) {
                    stack.add(file)
                }
            } else {
                if (fileFilter(file)) {
                    emit(file)
                }
            }
        }
    }
}

fun File.clear() {
    deleteRecursively()
}