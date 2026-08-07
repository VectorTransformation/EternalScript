package eternalScript.core.script.generation

import eternalScript.core.script.definition.ScriptCompilationCache
import eternalScript.core.script.classloading.ScriptGenerationClassLoader
import eternalScript.core.script.classloading.ScriptGenerationRegistry
import eternalScript.core.script.runtime.ManagedScriptRuntime
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
    runtimes: List<ManagedScriptRuntime>,
    private val generationRegistry: ScriptGenerationRegistry = ScriptGenerationRegistry(),
    cache: ScriptCompilationCache? = null,
    private val retainGenerationJar: (Path) -> Unit = { path ->
        cache?.retain(path.toFile())
    },
    private val releaseGenerationJar: (Path) -> Unit = { path ->
        cache?.release(path.toFile())
    }
) : GenerationRuntimeResource {
    private val runtimes = runtimes.toList()
    private val closed = AtomicBoolean()

    init {
        require(this.runtimes.isNotEmpty()) {
            "A generation runtime handle requires at least one Script instance."
        }

        retainGenerationJar(generationJar)
        val loaderAttached = mutableListOf<ManagedScriptRuntime>()
        try {
            this.runtimes.forEach { runtime ->
                runtime.executionGate.attachContextClassLoader(loader)
                loaderAttached += runtime
            }
            generationRegistry.register(loader)
        } catch (exception: Throwable) {
            loaderAttached.asReversed().forEach { runtime ->
                runtime.executionGate.detachContextClassLoader()
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
            generationRegistry.unregister(loader)
        }
        runtimes.forEach { runtime ->
            cleanup(failures) {
                runtime.executionGate.detachContextClassLoader()
            }
        }
        cleanup(failures, loader::close)
        cleanup(failures) {
            releaseGenerationJar(generationJar)
        }
        failures.throwCombined()
    }
}
