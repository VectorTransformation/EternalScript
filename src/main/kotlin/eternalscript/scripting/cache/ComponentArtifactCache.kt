package eternalscript.scripting.cache

import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.repl.k2.BatchCompiledScript
import eternalscript.scripting.repl.k2.CompiledComponent
import eternalscript.scripting.repl.k2.CompiledComponentArtifact
import eternalscript.scripting.repl.k2.CompiledComponentGeneration
import eternalscript.scripting.repl.k2.K2_REPL_COMPILER_ABI
import eternalscript.scripting.repl.k2.ScriptDependencyGraph
import eternalscript.scripting.repl.k2.ScriptClassifierDescriptor
import eternalscript.scripting.repl.k2.ScriptGraphResult
import eternalscript.util.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val COMPONENT_CACHE_FORMAT: Int = 5

@Serializable
private data class ComponentCacheIndex(
    val format: Int = COMPONENT_CACHE_FORMAT,
    val current: CachedObjectPointer? = null,
    val previous: CachedObjectPointer? = null
)

@Serializable
private data class CachedObjectPointer(
    val id: String,
    val manifestHash: String
)

@Serializable
private data class ComponentCacheManifest(
    val format: Int = COMPONENT_CACHE_FORMAT,
    val compilerAbi: String,
    val key: String,
    val environmentFingerprint: String,
    val sources: List<CachedSource>,
    val dependencies: Map<String, List<String>>,
    val initializationDependencies: Map<String, List<String>>,
    val initializationOrder: List<String>,
    val components: List<CachedComponent>
)

@Serializable
private data class CachedSource(val path: String, val hash: String)

@Serializable
private data class CachedComponent(
    val id: String,
    val paths: List<String>,
    val dependencies: List<String>,
    val jar: String,
    val jarHash: String,
    val scripts: List<CachedScript>
)

@Serializable
private data class CachedScript(
    val path: String,
    val className: String,
    val stateKey: String,
    val resultFieldName: String? = null,
    val classifiers: List<CachedClassifier> = emptyList()
)

@Serializable
private data class CachedClassifier(
    val name: String,
    val importPath: String,
    val kind: String
)

internal sealed interface ComponentCacheLookup {
    data class Hit(val key: String, val generation: CompiledComponentGeneration) : ComponentCacheLookup
    data class Miss(val reason: String) : ComponentCacheLookup
}

internal class ComponentArtifactCache(
    private val root: File,
    private val liveArtifactRoot: Path
) {
    private val objects = File(root, "objects")
    private val index = File(root, "index.json")

    fun lookup(
        sources: List<SharedReplSource>,
        environmentFingerprint: String
    ): ComponentCacheLookup {
        val expectedKey = cacheKey(sources, environmentFingerprint)
        val cacheIndex = runCatching { json.decodeFromString<ComponentCacheIndex>(index.readText(Charsets.UTF_8)) }
            .getOrElse { return ComponentCacheLookup.Miss("index-unavailable") }
        if (cacheIndex.format != COMPONENT_CACHE_FORMAT) return ComponentCacheLookup.Miss("index-format")

        val expectedSources = sources.sortedBy(SharedReplSource::name).map { CachedSource(it.name, it.hash) }
        var lastReason = "key-mismatch"
        listOfNotNull(cacheIndex.current, cacheIndex.previous).distinct().forEach { pointer ->
            val objectDirectory = File(objects, pointer.id)
            val manifest = runCatching {
                val manifestBytes = File(objectDirectory, "manifest.json").readBytes()
                check(Sha256.bytes(manifestBytes) == pointer.manifestHash) { "Manifest hash mismatch" }
                json.decodeFromString<ComponentCacheManifest>(manifestBytes.toString(Charsets.UTF_8))
            }.getOrElse {
                lastReason = "manifest-unavailable"
                return@forEach
            }
            if (
                manifest.format != COMPONENT_CACHE_FORMAT ||
                manifest.compilerAbi != K2_REPL_COMPILER_ABI ||
                manifest.key != expectedKey ||
                manifest.environmentFingerprint != environmentFingerprint ||
                manifest.sources != expectedSources
            ) {
                lastReason = "manifest-mismatch"
                return@forEach
            }
            val graphResult = ScriptDependencyGraph.create(
                manifest.sources.map(CachedSource::path),
                manifest.dependencies.mapValues { (_, providers) -> providers.toSet() },
                manifest.initializationDependencies.mapValues { (_, providers) -> providers.toSet() }
            )
            val graph = (graphResult as? ScriptGraphResult.Success)?.graph
            if (graph == null) {
                lastReason = "cached-init-cycle"
                return@forEach
            }
            if (
                graph.initializationOrder != manifest.initializationOrder ||
                graph.components.associate { component -> component.id to component.paths } !=
                manifest.components.associate { component -> component.id to component.paths }
            ) {
                lastReason = "graph-mismatch"
                return@forEach
            }

            val sourceByPath = sources.associateBy(SharedReplSource::name)
            val loaded = linkedMapOf<String, CompiledComponent>()
            try {
                manifest.components.forEach { cached ->
                    val jar = File(objectDirectory, cached.jar)
                    check(jar.isFile && Sha256.file(jar.toPath()) == cached.jarHash) {
                        "Component JAR failed validation: ${cached.id}"
                    }
                    val component = graph.components.single { candidate -> candidate.id == cached.id }
                    check(cached.dependencies == component.dependencies.sorted()) {
                        "Cached component dependencies do not match: ${cached.id}"
                    }
                    check(cached.scripts.map(CachedScript::path).sorted() == component.paths) {
                        "Cached component scripts do not match: ${cached.id}"
                    }
                    val artifact = CompiledComponentArtifact.copyFrom(
                        liveArtifactRoot,
                        cached.id,
                        jar.toPath(),
                        cached.jarHash
                    )
                    val componentSources = cached.paths.map(sourceByPath::getValue)
                    val scripts = cached.scripts.map { script ->
                        BatchCompiledScript(
                            sourceByPath.getValue(script.path),
                            script.className,
                            script.stateKey,
                            script.resultFieldName,
                            script.classifiers.map { classifier ->
                                ScriptClassifierDescriptor(
                                    classifier.name,
                                    classifier.importPath,
                                    classifier.kind
                                )
                            }
                        )
                    }
                    loaded[cached.id] = CompiledComponent(component, componentSources, scripts, artifact)
                }
                return ComponentCacheLookup.Hit(
                    expectedKey,
                    CompiledComponentGeneration(graph, sources, loaded.toMap())
                )
            } catch (error: Throwable) {
                loaded.values.forEach { component -> runCatching(component::close) }
                lastReason = "artifact-invalid:${error.message}"
            }
        }
        return ComponentCacheLookup.Miss(lastReason)
    }

    fun publish(
        generation: CompiledComponentGeneration,
        environmentFingerprint: String
    ): String {
        val key = cacheKey(generation.sources, environmentFingerprint)
        root.mkdirs()
        objects.mkdirs()
        val temporary = File(objects, ".$key-${UUID.randomUUID()}.tmp")
        val objectId = "$key-${UUID.randomUUID()}"
        val target = File(objects, objectId)
        var indexed = false
        check(temporary.mkdirs()) { "Could not create cache staging directory: ${temporary.absolutePath}" }
        try {
            val cachedComponents = generation.graph.componentOrder().map { component ->
                val compiled = generation.components.getValue(component.id)
                val jarName = "component-${component.id}.jar"
                val targetJar = File(temporary, jarName)
                Files.copy(compiled.artifact.jar, targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                CachedComponent(
                    component.id,
                    component.paths,
                    component.dependencies.sorted(),
                    jarName,
                    Sha256.file(targetJar.toPath()),
                    compiled.scripts.map { script ->
                        CachedScript(
                            script.source.name,
                            script.className,
                            script.stateKey,
                            script.resultFieldName,
                            script.classifiers.map { classifier ->
                                CachedClassifier(
                                    classifier.name,
                                    classifier.importPath,
                                    classifier.kind
                                )
                            }
                        )
                    }
                )
            }
            val manifest = ComponentCacheManifest(
                compilerAbi = K2_REPL_COMPILER_ABI,
                key = key,
                environmentFingerprint = environmentFingerprint,
                sources = generation.sources.sortedBy(SharedReplSource::name).map { source ->
                    CachedSource(source.name, source.hash)
                },
                dependencies = generation.graph.dependencies.mapValues { (_, providers) -> providers.sorted() },
                initializationDependencies = generation.graph.initializationDependencies
                    .mapValues { (_, providers) -> providers.sorted() },
                initializationOrder = generation.graph.initializationOrder,
                components = cachedComponents
            )
            val manifestFile = File(temporary, "manifest.json")
            manifestFile.writeText(json.encodeToString(manifest), Charsets.UTF_8)
            val pointer = CachedObjectPointer(objectId, Sha256.bytes(manifestFile.readBytes()))
            moveDirectory(temporary, target)

            val oldIndex = runCatching { json.decodeFromString<ComponentCacheIndex>(index.readText(Charsets.UTF_8)) }
                .getOrDefault(ComponentCacheIndex())
            val nextIndex = ComponentCacheIndex(current = pointer, previous = oldIndex.current)
            atomicWrite(index, json.encodeToString(nextIndex))
            indexed = true
            val retained = setOfNotNull(nextIndex.current?.id, nextIndex.previous?.id)
            objects.listFiles().orEmpty()
                .filter(File::isDirectory)
                .filter { directory ->
                    directory.name !in retained &&
                        (!directory.name.startsWith('.') || directory.name.endsWith(".tmp"))
                }
                .forEach { directory -> directory.deleteRecursively() }
            return key
        } finally {
            if (temporary.exists()) temporary.deleteRecursively()
            if (!indexed && target.exists()) target.deleteRecursively()
        }
    }

    private fun cacheKey(
        sources: List<SharedReplSource>,
        environmentFingerprint: String
    ): String = Sha256.bytes(
        buildString {
            appendLine("format=$COMPONENT_CACHE_FORMAT")
            appendLine("abi=$K2_REPL_COMPILER_ABI")
            appendLine("environment=$environmentFingerprint")
            sources.sortedBy(SharedReplSource::name).forEach { source ->
                appendLine("${source.name}\u0000${source.hash}")
            }
        }.toByteArray(Charsets.UTF_8)
    )

    private fun moveDirectory(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        try {
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    }
}
