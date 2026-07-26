package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.core.data.Resource
import eternalScript.core.script.data.ScriptPrefix
import eternalScript.core.script.data.ScriptSuffix
import eternalScript.core.extension.relativize
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.script.data.ScriptFile
import eternalScript.core.the.Root
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.bukkit.command.CommandSender
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object DataManager : Manager {
    private var job: Job? = null
    private val scriptLock = ConcurrentHashMap.newKeySet<String>()

    override fun register() {
        makeAll()
        compile()
    }

    fun makeAll() {
        listOf(
            Resource.DATA_FOLDER,
            Resource.LIBS,
            Resource.CACHE
        ).forEach(Resource::make)

        saveResource(Resource.SCRIPTS, *ScriptSuffix.SCRIPT.suffix)

        listOf(
            Resource.LANG
        ).forEach { resource ->
            saveResource(resource, *ScriptSuffix.LANG.suffix)
        }

        Root.register(ReloadManager)
    }

    fun saveResource(resource: Resource, vararg extension: String) {
        if (!resource.exists()) {
            val jarPath = javaClass.protectionDomain.codeSource.location.path
            val fileName = resource.file.nameWithoutExtension
            ZipFile(jarPath).use { jar ->
                jar.entries()
                    .asSequence()
                    .map(ZipEntry::getName)
                    .filter { name ->
                        name.startsWith(fileName) && extension.any(name::endsWith)
                    }.forEach { name ->
                        Root.INSTANCE.saveResource(name, false)
                    }
            }
        }
    }

    fun compile(sender: CommandSender? = null) {
        if (sender == null) {
            readAll()
        } else {
            reloadAll(sender)
        }
    }

    fun readAsync(sender: CommandSender? = null) {
        job = Root.launch {
            scripts().forEach { file ->
                launch {
                    loadAsync(file, sender)
                }
            }
        }
    }

    suspend fun loadAsync(file: File, sender: CommandSender? = null): Boolean? {
        val scriptFile = ScriptFile(file)

        if (scriptLock.contains(scriptFile.name)) {
            LangManager.sendMessage(sender, "script.operation.busy")
            return null
        }

        scriptLock.add(scriptFile.name)

        return try {
            Root.semaphore.withPermit {
                runCatching {
                    ScriptManager.load(scriptFile, sender)
                }.getOrDefault(false)
            }
        } finally {
            scriptLock.remove(scriptFile.name)
        }
    }

    fun checkAll(sender: CommandSender? = null) {
        if (isActive()) {
            LangManager.sendMessage(sender, "script.operation.busy")
            return
        }
        val files = scripts(all = true).toList()
        LangManager.sendMessage(sender, "script.check.all_started", args = listOf(files.size.toString()))
        job = Root.launch {
            val results = files.map { file ->
                async {
                    checkAsync(file, sender, announce = false)
                }
            }.awaitAll()
            val passed = results.count { it == true }
            LangManager.sendMessage(
                sender,
                "script.check.all_completed",
                args = listOf(files.size.toString(), passed.toString(), (files.size - passed).toString())
            )
        }
    }

    suspend fun checkAsync(file: File, sender: CommandSender? = null, announce: Boolean = true): Boolean? {
        val scriptFile = ScriptFile(file)

        if (scriptLock.contains(scriptFile.name)) {
            LangManager.sendMessage(sender, "script.operation.busy")
            return null
        }

        scriptLock.add(scriptFile.name)

        return try {
            Root.semaphore.withPermit {
                runCatching {
                    ScriptManager.check(scriptFile, sender, announce)
                }.getOrDefault(false)
            }
        } finally {
            scriptLock.remove(scriptFile.name)
        }
    }

    fun readSync(sender: CommandSender? = null) {
        scripts(isSync = true).forEach { file ->
            loadSync(file, sender)
        }
    }

    fun loadSync(file: File, sender: CommandSender? = null): Boolean? {
        val scriptFile = ScriptFile(file)

        if (scriptLock.contains(scriptFile.name)) {
            LangManager.sendMessage(sender, "script.operation.busy")
            return null
        }

        scriptLock.add(scriptFile.name)

        return try {
            runCatching {
                ScriptManager.load(scriptFile, sender)
            }.getOrDefault(false)
        } finally {
            scriptLock.remove(scriptFile.name)
        }
    }

    private fun reloadAll(sender: CommandSender) {
        if (isActive()) {
            LangManager.sendMessage(sender, "script.operation.busy")
            return
        }
        val syncFiles = scripts(isSync = true).toList()
        val asyncFiles = scripts().toList()
        val total = syncFiles.size + asyncFiles.size
        LangManager.sendMessage(sender, "script.reload.all_started", args = listOf(total.toString()))
        job = Root.launch {
            val available = (syncFiles + asyncFiles)
                .map { file -> file.relativize(Resource.SCRIPTS) }
                .toSet()
            (ScriptManager.scripts() - available).forEach { key ->
                ScriptManager.remove(key, sender, silent = true)
            }
            val syncResults = syncFiles.map { file ->
                loadSync(file, sender)
            }
            val asyncResults = asyncFiles.map { file ->
                async {
                    loadAsync(file, sender)
                }
            }.awaitAll()
            val success = (syncResults + asyncResults).count { it == true }
            LangManager.sendMessage(
                sender,
                "script.reload.all_completed",
                args = listOf(total.toString(), success.toString(), (total - success).toString())
            )
        }
    }

    fun readAll(sender: CommandSender? = null) {
        if (isActive()) {
            LangManager.sendMessage(sender, "script.operation.busy")
            return
        }
        ScriptManager.clear(sender, true)
        readSync(sender)
        readAsync(sender)
    }

    fun scripts(isSync: Boolean = false, all: Boolean = false) = Resource.SCRIPTS.searchAllSequence(
        { file ->
            if (!ScriptSuffix.SCRIPT.check(file)) return@searchAllSequence false
            if (ScriptPrefix.IGNORE.check(file)) return@searchAllSequence false
            if (!all && ScriptPrefix.SYNC.check(file) != isSync) return@searchAllSequence false
            true
        },
        { file ->
            if (ScriptPrefix.IGNORE.check(file)) return@searchAllSequence false
            if (!all && ScriptPrefix.SYNC.check(file) != isSync) return@searchAllSequence false
            true
        }
    )

    fun scriptPaths() = scripts(all = true).map { it.relativize(Resource.SCRIPTS) }

    fun isActive() = job?.isActive ?: false
}
