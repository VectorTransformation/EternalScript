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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

@Serializable
private data class ComponentCacheIndex(
    val format: Int = ScriptCacheLayout.CURRENT_FORMAT,
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
    val format: Int = ScriptCacheLayout.CURRENT_FORMAT,
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
        if (!hasNormalExistingDirectory(root.toPath()) || !hasNormalExistingDirectory(objects.toPath())) {
            return ComponentCacheLookup.Miss("storage-unavailable")
        }
        val expectedKey = cacheKey(sources, environmentFingerprint)
        val cacheIndex = runCatching { json.decodeFromString<ComponentCacheIndex>(index.readText(Charsets.UTF_8)) }
            .getOrElse { return ComponentCacheLookup.Miss("index-unavailable") }
        if (cacheIndex.format != ScriptCacheLayout.CURRENT_FORMAT) return ComponentCacheLookup.Miss("index-format")

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
                manifest.format != ScriptCacheLayout.CURRENT_FORMAT ||
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
        val objectsDirectory = prepareObjectsDirectory()
        val temporary = objectsDirectory.resolve(".$key-${UUID.randomUUID()}.tmp")
        val objectId = "$key-${UUID.randomUUID()}"
        val target = objectsDirectory.resolve(objectId)
        var indexed = false
        Files.createDirectory(temporary)
        try {
            val cachedComponents = generation.graph.componentOrder().map { component ->
                val compiled = generation.components.getValue(component.id)
                val jarName = "component-${component.id}.jar"
                val targetJar = temporary.resolve(jarName)
                Files.copy(compiled.artifact.jar, targetJar, StandardCopyOption.REPLACE_EXISTING)
                CachedComponent(
                    component.id,
                    component.paths,
                    component.dependencies.sorted(),
                    jarName,
                    Sha256.file(targetJar),
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
            val manifestFile = temporary.resolve("manifest.json")
            manifestFile.toFile().writeText(json.encodeToString(manifest), Charsets.UTF_8)
            val pointer = CachedObjectPointer(objectId, Sha256.bytes(Files.readAllBytes(manifestFile)))
            moveDirectory(temporary, target)

            val oldIndex = runCatching { json.decodeFromString<ComponentCacheIndex>(index.readText(Charsets.UTF_8)) }
                .getOrDefault(ComponentCacheIndex())
            val nextIndex = ComponentCacheIndex(current = pointer, previous = oldIndex.current)
            atomicWrite(index, json.encodeToString(nextIndex))
            indexed = true
            val retained = setOfNotNull(nextIndex.current?.id, nextIndex.previous?.id)
            cleanupObsoleteObjects(objectsDirectory, retained)
            return key
        } finally {
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { deleteOwnedTree(objectsDirectory, temporary) }
            }
            if (!indexed && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { deleteOwnedTree(objectsDirectory, target) }
            }
        }
    }

    private fun cacheKey(
        sources: List<SharedReplSource>,
        environmentFingerprint: String
    ): String = Sha256.bytes(
        buildString {
            appendLine("format=${ScriptCacheLayout.CURRENT_FORMAT}")
            appendLine("abi=$K2_REPL_COMPILER_ABI")
            appendLine("environment=$environmentFingerprint")
            sources.sortedBy(SharedReplSource::name).forEach { source ->
                appendLine("${source.name}\u0000${source.hash}")
            }
        }.toByteArray(Charsets.UTF_8)
    )

    private fun prepareObjectsDirectory(): Path {
        val rootDirectory = root.toPath().toAbsolutePath().normalize()
        if (!Files.exists(rootDirectory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(rootDirectory)
        requireNormalDirectory(rootDirectory, "Cache root")
        val objectsDirectory = rootDirectory.resolve("objects")
        if (!Files.exists(objectsDirectory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(objectsDirectory)
        requireNormalDirectory(objectsDirectory, "Cache objects directory")
        return objectsDirectory
    }

    private fun cleanupObsoleteObjects(objectsDirectory: Path, retained: Set<String>) {
        Files.list(objectsDirectory).use { entries ->
            entries.forEach { entry ->
                val name = entry.fileName.toString()
                if (
                    (isGeneratedObjectDirectory(name) && name !in retained) ||
                    isGeneratedTemporaryDirectory(name)
                ) {
                    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    runCatching { deleteOwnedTree(objectsDirectory, entry) }
                }
            }
        }
    }

    private fun deleteOwnedTree(objectsDirectory: Path, target: Path) {
        check(target.parent == objectsDirectory && target != objectsDirectory) {
            "Refusing to delete outside the component cache objects directory: $target"
        }
        requireNormalDirectory(objectsDirectory, "Cache objects directory")
        rejectUnsafeEntry(target)
        verifyTreeHasNoUnsafeEntries(target)
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attrs: BasicFileAttributes): FileVisitResult {
                rejectUnsafeEntry(directory, attrs)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                rejectUnsafeEntry(file, attrs)
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                rejectUnsafeEntry(directory)
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun verifyTreeHasNoUnsafeEntries(target: Path) {
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attrs: BasicFileAttributes): FileVisitResult {
                rejectUnsafeEntry(directory, attrs)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                rejectUnsafeEntry(file, attrs)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun hasNormalExistingDirectory(directory: Path): Boolean = runCatching {
        Files.exists(directory, LinkOption.NOFOLLOW_LINKS) &&
            !isUnsafeEntry(directory) &&
            Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)

    private fun requireNormalDirectory(directory: Path, label: String) {
        check(hasNormalExistingDirectory(directory)) { "$label is not a real directory: $directory" }
    }

    private fun rejectUnsafeEntry(path: Path, attributes: BasicFileAttributes? = null) {
        check(!isUnsafeEntry(path, attributes)) {
            "Refusing to traverse or delete cache link or reparse point: $path"
        }
    }

    private fun isUnsafeEntry(path: Path, attributes: BasicFileAttributes? = null): Boolean =
        Files.isSymbolicLink(path) ||
            (attributes ?: Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)).isOther

    private fun isGeneratedObjectDirectory(name: String): Boolean = objectDirectoryName.matches(name)

    private fun isGeneratedTemporaryDirectory(name: String): Boolean = temporaryObjectDirectoryName.matches(name)

    private fun moveDirectory(source: Path, target: Path) {
        runCatching {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source, target)
        }
    }

    private fun atomicWrite(target: File, content: String) {
        requireNormalDirectory(target.parentFile.toPath(), "Cache root")
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
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private companion object {
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val objectDirectoryName = Regex("[0-9a-f]{64}-$UUID_PATTERN")
        val temporaryObjectDirectoryName = Regex("\\.[0-9a-f]{64}-$UUID_PATTERN\\.tmp")
        val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    }
}
