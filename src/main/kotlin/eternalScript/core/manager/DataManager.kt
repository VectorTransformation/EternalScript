package eternalScript.core.manager

import eternalScript.api.manager.Manager
import eternalScript.core.data.Config
import eternalScript.core.data.Resource
import eternalScript.core.data.ScriptPrefix
import eternalScript.core.extension.searchAllSequence
import eternalScript.core.the.Root
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.bukkit.command.CommandSender
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.invariantSeparatorsPathString

object DataManager : Manager {
    private val EXTENSION = listOf("kt", "kts")
    private var job: Job? = null
    private val scriptLock = ConcurrentHashMap.newKeySet<String>()

    override fun register() {
        makeAll()
        compile()
    }

    fun makeAll() {
        listOf(
            Resource.DATA_FOLDER,
            Resource.LIBS
        ).forEach(Resource::make)

        listOf(
            Resource.SCRIPTS,
            Resource.UTILS
        ).forEach { resource ->
            saveResource(resource, *EXTENSION.toTypedArray())
        }

        listOf(
            Resource.LANG
        ).forEach { resource ->
            saveResource(resource, "json")
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
        job = Root.scope.launch {
            ConfigManager.value<List<String>>(Config.SCRIPTS).flatMap { script ->
                Resource.PLUGINS.child(script).searchAllSequence(
                    { file ->
                        val name = file.name
                        !ScriptPrefix.IGNORE.check(name) && file.extension in EXTENSION &&
                                !ScriptPrefix.SYNC.check(name)
                    },
                    { file ->
                        val name = file.name
                        !ScriptPrefix.IGNORE.check(name) &&
                                !ScriptPrefix.SYNC.check(name)
                    }
                )
            }.forEach { file ->
                launch {
                    val script = scriptPath(file)
                    val value = file.readText()
                    loadAsync(script, value, sender, true)
                }
            }
        }
    }

    suspend fun loadAsync(script: String, value: String, sender: CommandSender? = null, isCompile: Boolean = false) {
        if (scriptLock.contains(script)) {
            LangManager.sendMessage(sender, "script.wait")
            return
        }

        scriptLock.add(script)

        try {
            Root.semaphore.withPermit {
                runCatching {
                    ScriptManager.load(script, value, sender, isCompile)
                }
            }
        } finally {
            scriptLock.remove(script)
        }
    }

    fun readSync(sender: CommandSender? = null) {
        ConfigManager.value<List<String>>(Config.SCRIPTS).flatMap { script ->
            Resource.PLUGINS.child(script).searchAllSequence(
                { file ->
                    val name = file.name

                    if (ScriptPrefix.IGNORE.check(name) || file.extension !in EXTENSION) {
                        return@searchAllSequence false
                    }

                    scriptPath(file)
                        .split("/")
                        .mapNotNull { parent ->
                            parent.ifEmpty { null }
                        }.any { parent ->
                            ScriptPrefix.SYNC.check(parent)
                        }
                }
            )
        }.forEach { file ->
            val script = scriptPath(file)
            val value = file.readText()
            loadSync(script, value, sender, true)
        }
    }

    fun loadSync(script: String, value: String, sender: CommandSender? = null, isCompile: Boolean = false) {
        if (scriptLock.contains(script)) {
            LangManager.sendMessage(sender, "script.wait")
            return
        }

        scriptLock.add(script)

        try {
            runCatching {
                ScriptManager.load(script, value, sender, isCompile)
            }
        } finally {
            scriptLock.remove(script)
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

    fun scripts() = ConfigManager.value<List<String>>(Config.SCRIPTS).flatMap { script ->
        Resource.PLUGINS.child(script).searchAllSequence(
            { file ->
                val name = file.name
                !ScriptPrefix.IGNORE.check(name) && file.extension in EXTENSION
            },
            { file ->
                val name = file.name
                !ScriptPrefix.IGNORE.check(name)
            }
        )
    }.map(::scriptPath)

    fun utils() = ConfigManager.value<List<String>>(Config.UTILS).flatMap { util ->
        Resource.PLUGINS.child(util).searchAllSequence(
            { file ->
                val name = file.name
                !ScriptPrefix.IGNORE.check(name) && file.extension in EXTENSION
            },
            { file ->
                val name = file.name
                !ScriptPrefix.IGNORE.check(name)
            }
        )
    }.joinToString("\n", postfix = "\n\n") { util ->
        util.readText()
    }.trimStart()

    fun scriptPath(script: File) = relativize(script, Resource.PLUGINS)

    fun relativize(file: File, resource: Resource) =
        resource.file.toPath().relativize(file.toPath()).invariantSeparatorsPathString

    fun isActive() = job?.isActive ?: false
}