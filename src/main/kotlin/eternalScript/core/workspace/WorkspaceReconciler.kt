package eternalScript.core.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

internal class WorkspaceReconciler(
    workspaceRoot: Path,
    private val catalog: WorkspaceTemplateCatalog
) {
    private val root = workspaceRoot.toAbsolutePath().normalize()
    private val metadataRoot = root.resolve(METADATA_DIRECTORY)
    private val manifestPath = metadataRoot.resolve(MANIFEST_FILE)
    private val runtimeClasspathPath = metadataRoot.resolve(RUNTIME_CLASSPATH_FILE)

    @Synchronized
    fun reconcile(
        classpathEntries: Iterable<Path>,
        activePluginCount: Int
    ): WorkspaceUpdateResult {
        val normalizedClasspath = normalizeClasspath(classpathEntries)
        val created = linkedSetOf<String>()
        val updated = linkedSetOf<String>()
        val conflicts = linkedSetOf<String>()
        val errors = mutableListOf<String>()
        var ideFilesChanged = false

        runCatching {
            ensureDirectory(root)
            ensureDirectory(metadataRoot)
        }.onFailure { exception ->
            errors += exception.toWorkspaceError("create workspace directories")
        }

        val previousManifest = runCatching(::loadManifest).fold(
            onSuccess = { it },
            onFailure = { exception ->
                errors += exception.toWorkspaceError("read $METADATA_DIRECTORY/$MANIFEST_FILE")
                WorkspaceManifest.empty(catalog.templateVersion)
            }
        )
        val records = linkedMapOf<String, ManagedFileRecord>()

        catalog.managed.forEach { template ->
            val outcome = runCatching {
                reconcileManagedTemplate(
                    template = template,
                    previous = previousManifest.files[template.target]
                )
            }.getOrElse { exception ->
                errors += exception.toWorkspaceError("reconcile ${template.target}")
                ManagedTemplateOutcome(
                    record = previousManifest.files[template.target]
                        ?: ManagedFileRecord.unmanaged(template, catalog.templateVersion),
                    conflictPath = null,
                    write = FileWrite.UNCHANGED
                )
            }
            records[template.target] = outcome.record
            outcome.conflictPath?.let(conflicts::add)
            when (outcome.write) {
                FileWrite.CREATED -> {
                    created += template.target
                    ideFilesChanged = true
                }
                FileWrite.UPDATED -> {
                    updated += template.target
                    ideFilesChanged = true
                }
                FileWrite.UNCHANGED -> Unit
            }
        }

        runCatching {
            reconcileLocalOverride(catalog.localOverride)
        }.onSuccess { write ->
            when (write) {
                FileWrite.CREATED -> {
                    created += catalog.localOverride.target
                    ideFilesChanged = true
                }
                FileWrite.UPDATED,
                FileWrite.UNCHANGED -> Unit
            }
        }.onFailure { exception ->
            errors += exception.toWorkspaceError("create ${catalog.localOverride.target}")
        }

        runCatching {
            val content = normalizedClasspath.joinToString(
                separator = "\n",
                postfix = if (normalizedClasspath.isEmpty()) "" else "\n"
            ) { it.toString() }.toByteArray(StandardCharsets.UTF_8)
            atomicWriteIfChanged(runtimeClasspathPath, content)
        }.onSuccess { write ->
            when (write) {
                FileWrite.CREATED -> {
                    created += "$METADATA_DIRECTORY/$RUNTIME_CLASSPATH_FILE"
                    ideFilesChanged = true
                }
                FileWrite.UPDATED -> {
                    updated += "$METADATA_DIRECTORY/$RUNTIME_CLASSPATH_FILE"
                    ideFilesChanged = true
                }
                FileWrite.UNCHANGED -> Unit
            }
        }.onFailure { exception ->
            errors += exception.toWorkspaceError(
                "write $METADATA_DIRECTORY/$RUNTIME_CLASSPATH_FILE"
            )
        }

        val manifest = WorkspaceManifest(
            schemaVersion = DefaultWorkspaceTemplates.SCHEMA_VERSION,
            templateVersion = catalog.templateVersion,
            files = records
        )
        runCatching {
            atomicWriteIfChanged(
                manifestPath,
                WorkspaceManifestCodec.encode(manifest).toByteArray(StandardCharsets.UTF_8)
            )
        }.onSuccess { write ->
            when (write) {
                FileWrite.CREATED -> created += "$METADATA_DIRECTORY/$MANIFEST_FILE"
                FileWrite.UPDATED -> updated += "$METADATA_DIRECTORY/$MANIFEST_FILE"
                FileWrite.UNCHANGED -> Unit
            }
        }.onFailure { exception ->
            errors += exception.toWorkspaceError("write $METADATA_DIRECTORY/$MANIFEST_FILE")
        }

        val status = WorkspaceStatus(
            workspaceRoot = root,
            schemaVersion = DefaultWorkspaceTemplates.SCHEMA_VERSION,
            templateVersion = catalog.templateVersion,
            activePluginCount = activePluginCount.coerceAtLeast(0),
            classpathEntryCount = normalizedClasspath.size,
            conflictCount = conflicts.size,
            state = when {
                errors.isNotEmpty() -> WorkspaceState.ERROR
                conflicts.isNotEmpty() -> WorkspaceState.ACTION_REQUIRED
                else -> WorkspaceState.READY
            },
            lastError = errors.firstOrNull()
        )
        return WorkspaceUpdateResult(
            status = status,
            createdFiles = created.toList(),
            updatedFiles = updated.toList(),
            conflictFiles = conflicts.toList(),
            errors = errors.toList(),
            ideRefreshRecommended = ideFilesChanged
        )
    }

    @Synchronized
    fun inspect(
        classpathEntries: Iterable<Path>,
        activePluginCount: Int
    ): WorkspaceStatus {
        val normalizedClasspath = normalizeClasspath(classpathEntries)
        var actionRequired = false
        var error: String? = null
        var conflictCount = 0

        val manifest = runCatching(::loadManifest).getOrElse { exception ->
            actionRequired = true
            error = exception.toWorkspaceError("read $METADATA_DIRECTORY/$MANIFEST_FILE")
            WorkspaceManifest.empty(catalog.templateVersion)
        }

        catalog.managed.forEach { template ->
            val target = resolveTarget(template.target)
            val targetHash = runCatching {
                assertNoSymlinkTraversal(requireNotNull(target.parent))
                when {
                    Files.isSymbolicLink(target) -> error("Symbolic link is not managed: $target")
                    !Files.exists(target, LinkOption.NOFOLLOW_LINKS) -> null
                    Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) -> sha256(target)
                    else -> error("Managed workspace path is not a regular file: $target")
                }
            }.getOrElse { exception ->
                actionRequired = true
                error = exception.toWorkspaceError("inspect ${template.target}")
                null
            }
            val templateHash = sha256(template.content)
            if (targetHash == null) {
                actionRequired = true
            } else if (targetHash != templateHash) {
                val applied = manifest.files[template.target]?.appliedSha256
                if (applied == null || targetHash != applied) {
                    conflictCount += 1
                }
                actionRequired = true
            }
        }

        val expectedClasspath = normalizedClasspath.joinToString(
            separator = "\n",
            postfix = if (normalizedClasspath.isEmpty()) "" else "\n"
        ) { it.toString() }.toByteArray(StandardCharsets.UTF_8)
        assertNoSymlinkTraversal(requireNotNull(runtimeClasspathPath.parent))
        if (!sameContent(runtimeClasspathPath, expectedClasspath)) {
            actionRequired = true
        }

        return WorkspaceStatus(
            workspaceRoot = root,
            schemaVersion = DefaultWorkspaceTemplates.SCHEMA_VERSION,
            templateVersion = catalog.templateVersion,
            activePluginCount = activePluginCount.coerceAtLeast(0),
            classpathEntryCount = normalizedClasspath.size,
            conflictCount = conflictCount,
            state = when {
                error != null -> WorkspaceState.ERROR
                actionRequired -> WorkspaceState.ACTION_REQUIRED
                else -> WorkspaceState.READY
            },
            lastError = error
        )
    }

    private fun reconcileManagedTemplate(
        template: WorkspaceTemplate,
        previous: ManagedFileRecord?
    ): ManagedTemplateOutcome {
        val target = resolveTarget(template.target)
        assertNoSymlinkTraversal(requireNotNull(target.parent))
        val templateHash = sha256(template.content)
        if (Files.isSymbolicLink(target)) {
            error("Refusing to replace symbolic link: $target")
        }

        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            val write = atomicWriteIfChanged(target, template.content, template.executable)
            return ManagedTemplateOutcome(
                record = ManagedFileRecord.managed(template, catalog.templateVersion),
                conflictPath = null,
                write = write
            )
        }
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            "Managed workspace path is not a regular file: $target"
        }

        val currentHash = sha256(target)
        if (currentHash == templateHash) {
            return ManagedTemplateOutcome(
                record = ManagedFileRecord.managed(template, catalog.templateVersion),
                conflictPath = null,
                write = FileWrite.UNCHANGED
            )
        }

        if (previous?.appliedSha256 != null && currentHash == previous.appliedSha256) {
            val write = atomicWriteIfChanged(target, template.content, template.executable)
            return ManagedTemplateOutcome(
                record = ManagedFileRecord.managed(template, catalog.templateVersion),
                conflictPath = null,
                write = write
            )
        }

        val conflictRelative =
            "$METADATA_DIRECTORY/$CONFLICT_DIRECTORY/" +
                "${DefaultWorkspaceTemplates.SCHEMA_VERSION}/${template.target}"
        val conflictTarget = resolveTarget(conflictRelative)
        atomicWriteIfChanged(conflictTarget, template.content, template.executable)
        return ManagedTemplateOutcome(
            record = ManagedFileRecord(
                path = template.target,
                templateVersion = catalog.templateVersion,
                templateSha256 = templateHash,
                appliedSha256 = previous?.appliedSha256
            ),
            conflictPath = conflictRelative,
            write = FileWrite.UNCHANGED
        )
    }

    private fun reconcileLocalOverride(template: WorkspaceTemplate): FileWrite {
        val target = resolveTarget(template.target)
        assertNoSymlinkTraversal(requireNotNull(target.parent))
        if (Files.isSymbolicLink(target)) {
            error("Refusing to replace symbolic link: $target")
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                "Local workspace override is not a regular file: $target"
            }
            return FileWrite.UNCHANGED
        }
        return atomicWriteIfChanged(target, template.content, template.executable)
    }

    private fun loadManifest(): WorkspaceManifest {
        assertNoSymlinkTraversal(requireNotNull(manifestPath.parent))
        if (Files.isSymbolicLink(manifestPath)) {
            error("Refusing to read symbolic link manifest: $manifestPath")
        }
        if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return WorkspaceManifest.empty(catalog.templateVersion)
        }
        require(Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace manifest is not a regular file: $manifestPath"
        }
        val size = Files.size(manifestPath)
        require(size <= MAX_MANIFEST_BYTES) {
            "Workspace manifest exceeds $MAX_MANIFEST_BYTES bytes."
        }
        return WorkspaceManifestCodec.decode(
            Files.readString(manifestPath, StandardCharsets.UTF_8)
        )
    }

    private fun resolveTarget(relative: String): Path {
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root)) {
            "Workspace path escapes its root: $relative"
        }
        return resolved
    }

    private fun atomicWriteIfChanged(
        target: Path,
        content: ByteArray,
        executable: Boolean = false
    ): FileWrite {
        if (Files.isSymbolicLink(target)) {
            error("Refusing to replace symbolic link: $target")
        }
        val existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        if (existed) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                "Workspace path is not a regular file: $target"
            }
            if (sameContent(target, content)) {
                return FileWrite.UNCHANGED
            }
        }

        val parent = requireNotNull(target.parent) {
            "Workspace target has no parent: $target"
        }
        ensureDirectory(parent)
        assertNoSymlinkTraversal(parent, requireExisting = true)
        val temporary = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(
                temporary,
                content,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            applyPermissions(temporary, target, existed, executable)
            assertNoSymlinkTraversal(parent, requireExisting = true)
            require(!Files.isSymbolicLink(target)) {
                "Refusing to replace symbolic link: $target"
            }
            val stillExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            require(stillExists == existed) {
                "Workspace target changed while it was being prepared: $target"
            }
            if (stillExists) {
                require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    "Workspace path is not a regular file: $target"
                }
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (exception: AtomicMoveNotSupportedException) {
                throw IllegalStateException(
                    "Atomic replacement is not supported for $target.",
                    exception
                )
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return if (existed) FileWrite.UPDATED else FileWrite.CREATED
    }

    private fun ensureDirectory(directory: Path) {
        require(directory.normalize().startsWith(root)) {
            "Workspace directory escapes its root: $directory"
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root)
        }
        require(!Files.isSymbolicLink(root)) {
            "Workspace root must not be a symbolic link: $root"
        }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace root is not a directory: $root"
        }

        var current = root
        root.relativize(directory.normalize()).forEach { part ->
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) {
                    "Workspace directory contains a symbolic link: $current"
                }
                require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    "Workspace path is not a directory: $current"
                }
            } else {
                Files.createDirectory(current)
            }
        }
    }

    private fun assertNoSymlinkTraversal(
        directory: Path,
        requireExisting: Boolean = false
    ) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            require(!requireExisting) {
                "Workspace root does not exist: $root"
            }
            return
        }
        require(!Files.isSymbolicLink(root)) {
            "Workspace root must not be a symbolic link: $root"
        }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace root is not a directory: $root"
        }
        var current = root
        root.relativize(directory.normalize()).forEach { part ->
            current = current.resolve(part)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!requireExisting) {
                    "Workspace directory does not exist: $current"
                }
                return@forEach
            }
            require(!Files.isSymbolicLink(current)) {
                "Workspace directory contains a symbolic link: $current"
            }
            require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                "Workspace path is not a directory: $current"
            }
        }
    }

    private fun applyPermissions(
        temporary: Path,
        target: Path,
        targetExisted: Boolean,
        executable: Boolean
    ) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return
        }
        val permissions = if (targetExisted) {
            Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS).toMutableSet()
        } else {
            mutableSetOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
            )
        }
        if (executable) {
            permissions += PosixFilePermission.OWNER_EXECUTE
            permissions += PosixFilePermission.GROUP_EXECUTE
            permissions += PosixFilePermission.OTHERS_EXECUTE
        }
        Files.setPosixFilePermissions(temporary, permissions)
    }
}

private data class ManagedTemplateOutcome(
    val record: ManagedFileRecord,
    val conflictPath: String?,
    val write: FileWrite
)

private enum class FileWrite {
    CREATED,
    UPDATED,
    UNCHANGED
}

private const val METADATA_DIRECTORY = ".eternalscript"
private const val MANIFEST_FILE = "manifest.json"
private const val RUNTIME_CLASSPATH_FILE = "runtime-classpath.txt"
private const val CONFLICT_DIRECTORY = "conflicts"
private const val MAX_MANIFEST_BYTES = 1024L * 1024L
