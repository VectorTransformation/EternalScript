package eternalScript.core.script.generation

import eternalScript.api.script.Script
import eternalScript.core.script.definition.ScriptCompilationCache
import eternalScript.core.script.classloading.ScriptGenerationClassLoader
import eternalScript.core.script.classloading.ScriptGenerationRegistry
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal interface GenerationRuntimeResource : AutoCloseable {
    val pluginDependencies: Set<String>
}

/**
 * The single owner of resources shared by every Script in one generation.
 *
 * Per-Script registrations and tasks are disposed by [ScriptInstanceRuntime].
 * The class loader, compiled JAR lease, and plugin dependency state are
 * generation resources and therefore close exactly once here.
 */
internal class GenerationRuntimeHandle(
    private val loader: ScriptGenerationClassLoader,
    private val generationJar: Path,
    scripts: List<Script>,
    private val retainGenerationJar: (Path) -> Unit = { path ->
        ScriptCompilationCache.retain(path.toFile())
    },
    private val releaseGenerationJar: (Path) -> Unit = { path ->
        ScriptCompilationCache.release(path.toFile())
    }
) : GenerationRuntimeResource {
    private val scripts = scripts.toList()
    private val closed = AtomicBoolean()

    init {
        require(this.scripts.isNotEmpty()) {
            "A generation runtime handle requires at least one Script instance."
        }

        retainGenerationJar(generationJar)
        val loaderAttached = mutableListOf<Script>()
        try {
            this.scripts.forEach { script ->
                script.executionGate.attachContextClassLoader(loader)
                loaderAttached += script
            }
            ScriptGenerationRegistry.register(loader)
        } catch (exception: Throwable) {
            loaderAttached.asReversed().forEach { script ->
                script.executionGate.detachContextClassLoader()
            }
            releaseGenerationJar(generationJar)
            throw exception
        }
    }

    override val pluginDependencies: Set<String>
        get() = loader.pluginDependencies.snapshot()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        val failures = mutableListOf<Throwable>()
        cleanup(failures) {
            ScriptGenerationRegistry.unregister(loader)
        }
        scripts.forEach { script ->
            cleanup(failures) {
                script.executionGate.detachContextClassLoader()
            }
        }
        cleanup(failures, loader::close)
        cleanup(failures) {
            releaseGenerationJar(generationJar)
        }
        failures.throwCombined()
    }
}
