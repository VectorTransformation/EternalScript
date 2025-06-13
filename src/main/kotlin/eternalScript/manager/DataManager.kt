package eternalScript.manager

import eternalScript.data.Config
import eternalScript.data.Resource
import eternalScript.data.ScriptPrefix
import eternalScript.extension.searchAllSequence
import eternalScript.the.Root
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.bukkit.command.CommandSender
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object DataManager {
    private val EXTENSION = listOf("kt", "kts")
    private var job: Job? = null

    fun all() {
        makeAll()
        compile()
    }

    fun makeAll() {
        listOf(
            Resource.DATA_FOLDER,
            Resource.LIBS
        ).forEach(Resource::make)

        val scripts = Resource.SCRIPTS
        if (!scripts.exists()) {
            val jarPath = javaClass.protectionDomain.codeSource.location.path
            ZipFile(jarPath).use { jar ->
                jar.entries()
                    .asSequence()
                    .map(ZipEntry::getName)
                    .filter { name ->
                        name.startsWith("scripts") && (name.endsWith("kt") || name.endsWith("kts"))
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
                    Root.semaphore.withPermit {
                        runCatching {
                            val script = scriptPath(file)
                            val value = file.readText()
                            ScriptManager.load(script, value, sender, true)
                        }
                    }
                }
            }
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
            runCatching {
                val script = scriptPath(file)
                val value = file.readText()
                ScriptManager.load(script, value, sender, true)
            }
        }
    }

    fun readAll(sender: CommandSender? = null) {
        if (job?.isActive == true) {
            val result = "Script not loaded yet. Please wait."
            Root.sendInfo(sender, result)
            return
        }
        ScriptManager.clear(sender, true)
        if (ConfigManager.value(Config.DEBUG)) {
            val result = "Loaded Script"
            Root.sendInfo(sender, result)
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

    fun scriptPath(script: File) = filePath(script, Resource.PLUGINS)

    fun filePath(script: File, resource: Resource) = script.invariantSeparatorsPath.substring(resource.path().length)
}