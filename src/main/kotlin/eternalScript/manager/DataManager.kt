package eternalScript.manager

import eternalScript.data.Resource
import eternalScript.the.Root
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object DataManager {
    private val EXTENSION = listOf("kt", "kts")
    private val IGNORE = "-"
    private val cache = ConcurrentHashMap<Resource, YamlConfiguration>()
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

        if (!Resource.CONFIG.exists()) {
            Root.instance().saveDefaultConfig()
        }

        config()
    }

    fun config() {
        cache[Resource.CONFIG] = YamlConfiguration.loadConfiguration(Resource.CONFIG.file)
    }

    fun <T : Any> config(resource: Resource, key: String) = cache[resource]?.get(key) as? T

    fun compile(sender: CommandSender? = null) {
        readAll(sender)
    }

    fun readAll(sender: CommandSender? = null) {
        if (job?.isActive == true) {
            val result = "Script not loaded yet. Please wait."
            Root.sendInfo(sender, result)
            return
        }
        job = Root.scope.launch {
            Resource.SCRIPTS.searchAllSequence(
                { file ->
                    val name = file.name
                    !name.startsWith(IGNORE) && file.extension in EXTENSION
                },
                { file ->
                    val name = file.name
                    !name.startsWith(IGNORE)
                }
            ).forEach { file ->
                launch {
                    Root.semaphore.withPermit {
                        runCatching {
                            val script = scriptPath(file)
                            val value = file.readText()
                            ScriptManager.load(script, value, sender)
                        }
                    }
                }
            }
        }
    }

    fun scripts() = Resource.SCRIPTS.searchAllSequence(
        { file ->
            val name = file.name
            !name.startsWith(IGNORE) && file.extension in EXTENSION
        },
        { file ->
            val name = file.name
            !name.startsWith(IGNORE)
        }
    ).map(::scriptPath)

    fun scriptPath(script: File) = filePath(script, Resource.SCRIPTS)

    fun filePath(script: File, resource: Resource) = script.invariantSeparatorsPath.substring(resource.path().length)
}