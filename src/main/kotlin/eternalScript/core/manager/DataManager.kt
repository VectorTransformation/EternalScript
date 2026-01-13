package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.data.ScriptPrefix
import eternalScript.core.data.ScriptSuffix
import eternalScript.core.extension.relativize
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.script.ScriptFile
import eternalScript.core.the.Root
import kotlinx.coroutines.Job
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

        listOf(
            Resource.SCRIPTS,
            Resource.UTILS
        ).forEach { resource ->
            saveResource(resource, *ScriptSuffix.SCRIPT.suffix)
        }

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
                        Root.instance().saveResource(name, false)
                    }
            }
        }
    }

    fun compile(sender: CommandSender? = null) {
        readAll(sender)
    }

    fun readAsync(sender: CommandSender? = null) {
        job = Root.launch {
            scripts().forEach { file ->
                launch {
                    loadAsync(file, sender, true)
                }
            }
        }
    }

    suspend fun loadAsync(file: File, sender: CommandSender? = null, isCompile: Boolean = false) {
        val scriptFile = ScriptFile(file)

        if (scriptLock.contains(scriptFile.name)) {
            LangManager.sendMessage(sender, "script.wait")
            return
        }

        scriptLock.add(scriptFile.name)

        try {
            Root.semaphore.withPermit {
                runCatching {
                    ScriptManager.load(scriptFile, sender, isCompile)
                }
            }
        } finally {
            scriptLock.remove(scriptFile.name)
        }
    }

    fun readSync(sender: CommandSender? = null) {
        scripts(isSync = true).forEach { file ->
            loadSync(file, sender, true)
        }
    }

    fun loadSync(file: File, sender: CommandSender? = null, isCompile: Boolean = false) {
        val scriptFile = ScriptFile(file)

        if (scriptLock.contains(scriptFile.name)) {
            LangManager.sendMessage(sender, "script.wait")
            return
        }

        scriptLock.add(scriptFile.name)

        try {
            runCatching {
                ScriptManager.load(scriptFile, sender, isCompile)
            }
        } finally {
            scriptLock.remove(scriptFile.name)
        }
    }

    fun readAll(sender: CommandSender? = null) {
        if (isActive()) {
            LangManager.sendMessage(sender, "script.wait")
            return
        }
        ScriptManager.clear(sender, true)
        if (ConfigManager.value(Config.DEBUG)) {
            LangManager.sendMessage(sender, "script.loaded")
        }
        readSync(sender)
        readAsync(sender)
    }

    fun scripts(config: Config = Config.SCRIPTS, isSync: Boolean = false, all: Boolean = false) = ConfigManager.value<List<String>>(config).flatMap { script ->
        Resource.PLUGINS.child(script).searchAllSequence(
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
    }

    fun scriptPaths(config: Config = Config.SCRIPTS) = scripts(config).map(File::relativize)

    fun utils() = scripts(Config.UTILS, all = true).joinToString(
        "\n\n",
        "\n\n",
        transform = File::readText
    )

    fun isActive() = job?.isActive ?: false
}