package eternalscript.intellij.model

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.Alarm
import eternalscript.intellij.analysis.AbiAnalyzer
import eternalscript.intellij.analysis.DeclarationEnvironmentIndex
import eternalscript.intellij.analysis.IndexedScriptFile
import eternalscript.intellij.analysis.ScriptFileIndex
import eternalscript.intellij.analysis.SyntheticModelPublisher
import eternalscript.intellij.resolve.Idea262Facade
import eternalscript.intellij.scripting.ScriptConfigurationCoordinator
import eternalscript.intellij.workspace.EternalScriptWorkspaceDescriptor
import eternalscript.intellij.workspace.WorkspaceRegistry
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class EternalScriptProjectService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(EternalScriptProjectService::class.java)
    private val models = EternalScriptProjectModelStore()
    private val descriptors = AtomicReference<Map<String, EternalScriptWorkspaceDescriptor>>(emptyMap())
    private val started = AtomicBoolean()
    private val manualTestAnalysis = AtomicBoolean()
    private val publicationScheduled = AtomicBoolean()
    private val syntheticInvalidationRequested = AtomicBoolean()
    private val syntheticInvalidationScheduled = AtomicBoolean()
    private val publishedSnapshot = AtomicReference(EternalScriptProjectSnapshot.EMPTY)
    private val registryEpoch = AtomicLong()
    // Record a source change before resolving its owner. Discovery scans without holding the VFS
    // listener, so an event can otherwise be resolved against the previous descriptor map and
    // be dropped (or queued under a parent that is about to gain a nested workspace).
    private val relevantSourceChangeEpoch = AtomicLong()
    // Cancelling an Alarm request does not stop discovery that is already running on the pool.
    // Serialize the full discovery transaction so an older scan cannot publish side effects
    // after a newer scan has started.
    private val discoveryAnalysisLock = Any()
    private val workspaceEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val workspaceAnalysisGate = WorkspaceAnalysisGate()
    private val discoveryAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val workspaceAlarms = ConcurrentHashMap<String, Alarm>()
    private val pendingPaths = ConcurrentHashMap<String, MutableSet<Path>>()
    private val pendingUrls = ConcurrentHashMap<String, MutableSet<String>>()
    private val stabilizationCounts = ConcurrentHashMap<String, MutableMap<String, Int>>()
    private val testBasePath = AtomicReference<Path?>(null)
    private val beforeDescriptorPublicationForTests = AtomicReference<(() -> Unit)?>(null)
    private val sourceChangeRediscoveryRequestsForTests = AtomicLong()
    private val registry = WorkspaceRegistry(project)
    private val fileIndex = ScriptFileIndex()
    private val analyzer = AbiAnalyzer(project)
    private val configurations = ScriptConfigurationCoordinator(
        project,
        models::snapshot
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!isEternalScript(file)) return
                    runCatching { file.toNioPath().toAbsolutePath().normalize() }.getOrNull()
                        ?.let { path -> markChanged(path, waitForCommit = true) }
                }
            },
            this
        )
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val plan = EternalScriptVfsChangePlanner.plan(events)
                    if (plan.rediscover) {
                        scheduleDiscovery()
                        return
                    }
                    plan.changedPaths.forEach { path -> markChanged(path, waitForCommit = false) }
                }
            }
        )
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    configurations.fileOpened(file)
                }
            }
        )
        Idea262Facade.subscribeToScriptConfigurationChanges(project, this) { file ->
            workspaceFor(file)?.let { workspace ->
                log.debug("EternalScript configuration changed for ${file.url}")
                if (configurations.configurationChanged(file)) scheduleUnstable(workspace.id)
            }
        }
        val dumbService = DumbService.getInstance(project)
        if (dumbService.isDumb) {
            dumbService.runWhenSmart { scheduleDiscovery(delay = 0) }
        } else {
            scheduleDiscovery(delay = 0)
        }
    }

    fun current(): EternalScriptProjectSnapshot = models.snapshot()

    fun workspaceFor(file: VirtualFile): EternalScriptWorkspace? = current().workspaceFor(file)

    fun definitionWorkspaces(): List<EternalScriptWorkspace> = current().workspaces

    fun definitionConfiguration(workspace: EternalScriptWorkspace): ScriptCompilationConfiguration =
        configurations.definitionConfiguration(workspace)

    fun scriptDefinitionRegistered() = configurations.definitionRegistered()

    @TestOnly
    fun setScriptConfigurationReloadsEnabledForTests(enabled: Boolean) =
        configurations.setReloadsEnabledForTests(enabled)

    fun scheduleDiscovery(delay: Int = REFRESH_DELAY_MILLIS) {
        if (project.isDisposed || manualTestAnalysis.get()) return
        val epoch = registryEpoch.incrementAndGet()
        discoveryAlarm.cancelAllRequests()
        discoveryAlarm.addRequest({ performDiscovery(epoch, testBasePath.get(), synchronous = false) }, delay)
    }

    @TestOnly
    @KaAllowAnalysisOnEdt
    fun rebuildSynchronouslyForTests(basePath: Path? = null) {
        check(ApplicationManager.getApplication().isUnitTestMode)
        enterManualTestAnalysis()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        pendingPaths.clear()
        pendingUrls.clear()
        val base = (basePath ?: testBasePath.get() ?: Path.of(requireNotNull(project.basePath)))
            .toAbsolutePath()
            .normalize()
        testBasePath.set(base)
        val epoch = registryEpoch.incrementAndGet()
        allowAnalysisOnEdt {
            performDiscovery(epoch, base, synchronous = true)
        }
    }

    @TestOnly
    fun indexedScanCountForTests(): Long = fileIndex.scanCount()

    @TestOnly
    fun seedWorkspaceWorkStateForTests(workspaceId: String, path: Path, sourceUrl: String) {
        check(ApplicationManager.getApplication().isUnitTestMode)
        workspaceEpochs.computeIfAbsent(workspaceId) { AtomicLong() }.incrementAndGet()
        workspaceAlarms.computeIfAbsent(workspaceId) { Alarm(Alarm.ThreadToUse.POOLED_THREAD, this) }
        pendingPaths.computeIfAbsent(workspaceId) { ConcurrentHashMap.newKeySet() }.add(path)
        pendingUrls.computeIfAbsent(workspaceId) { ConcurrentHashMap.newKeySet() }.add(sourceUrl)
        stabilizationCounts.computeIfAbsent(workspaceId) { ConcurrentHashMap() }[sourceUrl] = 1
    }

    @TestOnly
    fun retireWorkspaceWorkStateForTests(validIds: Set<String>) {
        check(ApplicationManager.getApplication().isUnitTestMode)
        retireMissingWorkspaceState(validIds)
    }

    @TestOnly
    fun withWorkspaceAnalysisGateForTests(workspaceId: String, action: () -> Unit) {
        check(ApplicationManager.getApplication().isUnitTestMode)
        workspaceAnalysisGate.withWorkspace(workspaceId, action)
    }

    @TestOnly
    fun hasWorkspaceWorkStateForTests(workspaceId: String): Boolean =
        workspaceAlarms.containsKey(workspaceId) ||
            pendingPaths.containsKey(workspaceId) ||
            pendingUrls.containsKey(workspaceId) ||
            stabilizationCounts.containsKey(workspaceId)

    @TestOnly
    fun scriptConfigurationChangedForTests(file: VirtualFile): Boolean =
        configurations.configurationChanged(file)

    @TestOnly
    fun shouldRetryDiscoveryAfterCancellationForTests(
        synchronous: Boolean,
        projectDisposed: Boolean,
        attemptedEpoch: Long,
        currentEpoch: Long
    ): Boolean = shouldRetryDiscoveryAfterCancellation(
        synchronous,
        projectDisposed,
        attemptedEpoch,
        currentEpoch
    )

    @TestOnly
    fun setBeforeDescriptorPublicationForTests(action: (() -> Unit)?) {
        check(ApplicationManager.getApplication().isUnitTestMode)
        beforeDescriptorPublicationForTests.set(action)
    }

    @TestOnly
    fun markChangedForTests(path: Path) {
        check(ApplicationManager.getApplication().isUnitTestMode)
        markChanged(path, waitForCommit = false)
    }

    @TestOnly
    fun pendingWorkspaceForPathForTests(path: Path): String? {
        check(ApplicationManager.getApplication().isUnitTestMode)
        val normalized = path.toAbsolutePath().normalize()
        return pendingPaths.entries.singleOrNull { normalized in it.value }?.key
    }

    @TestOnly
    fun sourceChangeRediscoveryRequestsForTests(): Long {
        check(ApplicationManager.getApplication().isUnitTestMode)
        return sourceChangeRediscoveryRequestsForTests.get()
    }

    @TestOnly
    @KaAllowAnalysisOnEdt
    fun analyzePathSynchronouslyForTests(path: Path) {
        check(ApplicationManager.getApplication().isUnitTestMode)
        enterManualTestAnalysis()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val normalized = path.toAbsolutePath().normalize()
        val descriptor = registry.nearest(descriptors.get().values, normalized)
            ?: error("No EternalScript workspace owns $normalized")
        pendingPaths.computeIfAbsent(descriptor.id) { ConcurrentHashMap.newKeySet() }.add(normalized)
        val epoch = workspaceEpochs.computeIfAbsent(descriptor.id) { AtomicLong() }.incrementAndGet()
        allowAnalysisOnEdt {
            performWorkspaceAnalysis(descriptor.id, epoch, synchronous = true)
        }
    }

    override fun dispose() {
        registryEpoch.incrementAndGet()
        discoveryAlarm.cancelAllRequests()
        workspaceAlarms.values.forEach(Alarm::cancelAllRequests)
        pendingPaths.clear()
        pendingUrls.clear()
    }

    private fun performDiscovery(epoch: Long, baseOverride: Path?, synchronous: Boolean) {
        synchronized(discoveryAnalysisLock) {
            performDiscoveryLocked(epoch, baseOverride, synchronous)
        }
    }

    private fun performDiscoveryLocked(epoch: Long, baseOverride: Path?, synchronous: Boolean) {
        if (project.isDisposed || registryEpoch.get() != epoch) return
        val sourceChangeEpochAtStart = relevantSourceChangeEpoch.get()
        try {
            val base = baseOverride ?: project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
                ?: return
            val manifests = if (baseOverride != null) {
                registry.diskManifestPaths(base)
            } else {
                ReadAction.computeCancellable<List<Path>, RuntimeException> { registry.indexedManifestPaths(base) }
            }
            val descriptorList = registry.load(manifests)
            if (registryEpoch.get() != epoch) return
            val previousSnapshot = current()
            val previous = previousSnapshot.workspaces.associateBy(EternalScriptWorkspace::id)
            val descriptorById = descriptorList.associateBy(EternalScriptWorkspaceDescriptor::id)
            val discoveredByWorkspace = linkedMapOf<String, Map<String, IndexedScriptFile>>()
            val nextWorkspaces = descriptorList.mapNotNull { descriptor ->
                val discovered = fileIndex.enumerate(descriptor).filterValues { file ->
                    registry.nearest(descriptorList, file.path)?.id == descriptor.id
                }
                discoveredByWorkspace[descriptor.id] = discovered
                val oldWorkspace = previous[descriptor.id]
                val oldAbis = oldWorkspace?.fileAbis.orEmpty()
                val abis = linkedMapOf<String, EternalScriptFileAbi>()
                val sourceUrls = discovered.keys.toCollection(linkedSetOf())
                val declarationEnvironment = DeclarationEnvironmentIndex(oldAbis, sourceUrls)
                discovered.values.forEach { file ->
                    checkCanceled(epoch)
                    analyzer.analyze(
                        file,
                        oldAbis[file.url],
                        declarationEnvironment.excluding(file.url)
                    )?.let { abi ->
                        abis[file.url] = abi
                        declarationEnvironment.replace(file.url, abi)
                    }
                }
                SyntheticModelPublisher.build(descriptor, discovered, abis, previous[descriptor.id]).workspace
            }
            if (registryEpoch.get() != epoch || project.isDisposed) return
            val next = snapshotOf(nextWorkspaces, previousSnapshot.version + 1)
            beforeDescriptorPublicationForTests.get()?.invoke()
            val transactionWorkspaceIds = buildSet {
                addAll(previous.keys)
                addAll(descriptorById.keys)
                addAll(descriptors.get().keys)
                addAll(workspaceEpochs.keys)
                addAll(workspaceAlarms.keys)
                addAll(pendingPaths.keys)
                addAll(pendingUrls.keys)
                addAll(stabilizationCounts.keys)
            }
            var reroutedWorkspaceIds = emptySet<String>()
            val accepted = workspaceAnalysisGate.withWorkspaces(transactionWorkspaceIds) {
                if (project.isDisposed || registryEpoch.get() != epoch) return@withWorkspaces false
                if (!publishSnapshot(previousSnapshot, next, synchronous)) return@withWorkspaces false

                // Install every index before exposing the matching descriptor map. Incremental
                // analysis for any affected workspace remains behind the same retained gates, so
                // it cannot observe the new model with an old or partially installed file index.
                discoveredByWorkspace.forEach(fileIndex::replace)
                descriptors.set(descriptorById)
                reroutedWorkspaceIds = rehomePendingPaths(descriptorById)
                retireMissingWorkspaceState(descriptorById.keys, previous.keys)
                true
            }
            if (accepted && !project.isDisposed && registryEpoch.get() == epoch) {
                // This comparison must stay after descriptor publication. A change that resolved
                // the old descriptor map is now replayed through discovery; a later change sees
                // the new map and follows its ordinary incremental queue.
                reroutedWorkspaceIds.forEach { workspaceId ->
                    scheduleWorkspace(workspaceId, waitForCommit = true, delay = REFRESH_DELAY_MILLIS)
                }
                if (relevantSourceChangeEpoch.get() != sourceChangeEpochAtStart) {
                    sourceChangeRediscoveryRequestsForTests.incrementAndGet()
                    scheduleDiscovery(delay = 0)
                }
            } else if (!accepted && !project.isDisposed && registryEpoch.get() == epoch) {
                scheduleDiscovery(delay = 0)
            }
        } catch (cancelled: ProcessCanceledException) {
            if (shouldRetryDiscoveryAfterCancellation(synchronous, project.isDisposed, epoch, registryEpoch.get())) {
                scheduleDiscovery()
            }
            throw cancelled
        } catch (error: Throwable) {
            log.warn("EternalScript workspace discovery failed", error)
            if (synchronous) throw error
        }
    }

    private fun shouldRetryDiscoveryAfterCancellation(
        synchronous: Boolean,
        projectDisposed: Boolean,
        attemptedEpoch: Long,
        currentEpoch: Long
    ): Boolean = !synchronous && !projectDisposed && attemptedEpoch == currentEpoch

    private fun markChanged(path: Path, waitForCommit: Boolean) {
        relevantSourceChangeEpoch.incrementAndGet()
        val normalized = path.toAbsolutePath().normalize()
        val descriptor = registry.nearest(descriptors.get().values, normalized) ?: return
        stabilizationCounts.remove(descriptor.id)
        pendingPaths.computeIfAbsent(descriptor.id) { ConcurrentHashMap.newKeySet() }.add(normalized)
        scheduleWorkspace(descriptor.id, waitForCommit, REFRESH_DELAY_MILLIS)
    }

    /**
     * A descriptor publication can introduce a nested workspace or move a script root. Paths
     * queued while the old map was visible must follow the new nearest owner before any retained
     * incremental worker can update its file index.
     */
    private fun rehomePendingPaths(
        descriptorById: Map<String, EternalScriptWorkspaceDescriptor>
    ): Set<String> {
        val reroutedWorkspaceIds = linkedSetOf<String>()
        pendingPaths.forEach { (workspaceId, paths) ->
            paths.toList().forEach { path ->
                val ownerId = registry.nearest(descriptorById.values, path)?.id ?: return@forEach
                if (ownerId != workspaceId && paths.remove(path)) {
                    pendingPaths.computeIfAbsent(ownerId) { ConcurrentHashMap.newKeySet() }.add(path)
                    reroutedWorkspaceIds += ownerId
                }
            }
            if (paths.isEmpty()) pendingPaths.remove(workspaceId, paths)
        }
        return reroutedWorkspaceIds
    }

    private fun retireMissingWorkspaceState(validIds: Set<String>, knownIds: Set<String> = emptySet()) {
        val missingIds = buildSet {
            addAll(knownIds)
            addAll(workspaceEpochs.keys)
            addAll(workspaceAlarms.keys)
            addAll(pendingPaths.keys)
            addAll(pendingUrls.keys)
            addAll(stabilizationCounts.keys)
        }.filterNotTo(linkedSetOf(), validIds::contains)

        workspaceAnalysisGate.withWorkspaces(missingIds) {
            missingIds.forEach { workspaceId ->
                // Keep an incremented epoch tombstone so a recreated deterministic workspace id
                // cannot make a callback from its previous incarnation current again.
                workspaceEpochs.computeIfAbsent(workspaceId) { AtomicLong() }.incrementAndGet()
                workspaceAlarms.remove(workspaceId)?.cancelAllRequests()
                pendingPaths.remove(workspaceId)
                pendingUrls.remove(workspaceId)
                stabilizationCounts.remove(workspaceId)
            }
            fileIndex.removeMissingWorkspaces(validIds)
        }
    }

    private fun scheduleAffected(workspaceId: String, urls: Collection<String>) {
        if (urls.isEmpty()) return
        val pending = pendingUrls.computeIfAbsent(workspaceId) { ConcurrentHashMap.newKeySet() }
        val newlyPending = urls.filter(pending::add)
        if (newlyPending.isEmpty()) return
        val counts = stabilizationCounts.computeIfAbsent(workspaceId) { ConcurrentHashMap() }
        val accepted = newlyPending.filter { url ->
            val count = counts.merge(url, 1, Int::plus) ?: 1
            count <= MAX_STABILIZATION_PASSES
        }
        val rejected = newlyPending.toSet() - accepted.toSet()
        pending.removeAll(rejected.toSet())
        if (accepted.isEmpty()) return
        scheduleWorkspace(workspaceId, waitForCommit = false, STABILIZATION_DELAY_MILLIS)
    }

    private fun scheduleUnstable(workspaceId: String) {
        val workspace = current().workspaces.firstOrNull { candidate -> candidate.id == workspaceId } ?: return
        val unstable = workspace.sourceUrls.filter { url ->
            workspace.fileAbis[url]?.let { abi -> !abi.stable && abi.retryable } == true
        }
        scheduleAffected(workspaceId, unstable)
    }

    private fun scheduleWorkspace(workspaceId: String, waitForCommit: Boolean, delay: Int) {
        if (project.isDisposed || manualTestAnalysis.get()) return
        val epoch = workspaceEpochs.computeIfAbsent(workspaceId) { AtomicLong() }.incrementAndGet()
        val alarm = workspaceAlarms.computeIfAbsent(workspaceId) {
            Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
        }
        alarm.cancelAllRequests()
        val submit = {
            if (!project.isDisposed && workspaceEpochs[workspaceId]?.get() == epoch) {
                alarm.addRequest({ performWorkspaceAnalysis(workspaceId, epoch, synchronous = false) }, delay)
            }
        }
        if (waitForCommit) {
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted(submit)
            }, ModalityState.defaultModalityState())
        } else {
            submit()
        }
    }

    private fun performWorkspaceAnalysis(workspaceId: String, epoch: Long, synchronous: Boolean) {
        workspaceAnalysisGate.withWorkspace(workspaceId) {
            performWorkspaceAnalysisLocked(workspaceId, epoch, synchronous)
        }
    }

    private fun performWorkspaceAnalysisLocked(workspaceId: String, epoch: Long, synchronous: Boolean) {
        if (project.isDisposed || workspaceEpochs[workspaceId]?.get() != epoch) return
        val drainedPaths = drain(pendingPaths, workspaceId)
        val drainedUrls = drain(pendingUrls, workspaceId)
        try {
            val descriptor = descriptors.get()[workspaceId] ?: return
            val previousSnapshot = current()
            val previous = previousSnapshot.workspaces.firstOrNull { workspace -> workspace.id == workspaceId }
                ?: return
            checkWorkspaceEpoch(workspaceId, epoch)
            val oldFiles = fileIndex.files(workspaceId)
            val dirtyUrls = linkedSetOf<String>()
            val reroutedWorkspaceIds = linkedSetOf<String>()
            drainedPaths.forEach { path ->
                checkWorkspaceEpoch(workspaceId, epoch)
                val ownerId = registry.nearest(descriptors.get().values, path)?.id
                if (ownerId != workspaceId) {
                    if (ownerId != null) {
                        pendingPaths.computeIfAbsent(ownerId) { ConcurrentHashMap.newKeySet() }.add(path)
                        reroutedWorkspaceIds += ownerId
                    }
                    return@forEach
                }
                oldFiles.values.firstOrNull { file -> file.path == path }?.url?.let(dirtyUrls::add)
                fileIndex.update(descriptor, path)?.url?.let(dirtyUrls::add)
                checkWorkspaceEpoch(workspaceId, epoch)
            }
            dirtyUrls += drainedUrls
            val files = fileIndex.files(workspaceId)
            val abis = previous.fileAbis.toMutableMap()
            abis.keys.retainAll(files.keys)
            val sourceUrls = files.keys.toCollection(linkedSetOf())
            val declarationEnvironment = DeclarationEnvironmentIndex(abis, sourceUrls)
            dirtyUrls.forEach { url ->
                checkWorkspaceEpoch(workspaceId, epoch)
                val file = files[url] ?: return@forEach
                analyzer.analyze(
                    file,
                    abis[url],
                    declarationEnvironment.excluding(file.url)
                )?.let { abi ->
                    abis[url] = abi
                    declarationEnvironment.replace(url, abi)
                }
            }
            val published = SyntheticModelPublisher.build(descriptor, files, abis, previous)
            checkWorkspaceEpoch(workspaceId, epoch)
            val nextWorkspaces = previousSnapshot.workspaces.map { workspace ->
                if (workspace.id == workspaceId) published.workspace else workspace
            }
            val next = snapshotOf(nextWorkspaces, previousSnapshot.version + 1)
            val accepted = publishSnapshot(previousSnapshot, next, synchronous)
            if (!accepted) {
                restorePending(workspaceId, drainedPaths, drainedUrls)
                if (!synchronous && !project.isDisposed) {
                    scheduleWorkspace(workspaceId, waitForCommit = false, delay = 0)
                }
                return
            }
            val affectedByAbi = published.workspace.sourceUrls.filter { url ->
                val abi = published.workspace.fileAbis[url] ?: return@filter false
                abi.referencedNames.any(published.changedDeclarationNames::contains) &&
                    (url !in dirtyUrls || !abi.stable)
            }
            val stillUnstable = dirtyUrls.filter { url ->
                url in published.workspace.sourceUrls && published.workspace.fileAbis[url]?.let { abi ->
                    !abi.stable && abi.retryable
                } == true
            }
            scheduleAffected(workspaceId, affectedByAbi + stillUnstable)
            reroutedWorkspaceIds.forEach { ownerId ->
                scheduleWorkspace(ownerId, waitForCommit = true, delay = REFRESH_DELAY_MILLIS)
            }
        } catch (cancelled: ProcessCanceledException) {
            restorePending(workspaceId, drainedPaths, drainedUrls)
            if (!synchronous && !project.isDisposed && workspaceEpochs[workspaceId]?.get() == epoch) {
                scheduleWorkspace(workspaceId, waitForCommit = false, delay = 0)
            }
            throw cancelled
        } catch (error: Throwable) {
            restorePending(workspaceId, drainedPaths, drainedUrls)
            log.warn("EternalScript incremental analysis failed for $workspaceId", error)
            if (synchronous) throw error
        }
    }

    private fun publishSnapshot(
        expected: EternalScriptProjectSnapshot,
        next: EternalScriptProjectSnapshot,
        synchronous: Boolean
    ): Boolean {
        configurations.prepareSnapshotPublication(expected, next)
        if (!models.compareAndSet(expected, next)) return false
        if (synchronous) {
            publishLatestSnapshotSynchronously()
        } else {
            scheduleSnapshotPublication()
        }
        return true
    }

    private fun publishLatestSnapshotSynchronously() {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            publishLatestSnapshotOnEdt(deferSyntheticInvalidation = false)
        } else {
            application.invokeAndWait(
                { publishLatestSnapshotOnEdt(deferSyntheticInvalidation = false) },
                ModalityState.defaultModalityState()
            )
        }
    }

    private fun scheduleSnapshotPublication() {
        if (project.isDisposed || !publicationScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            try {
                publishLatestSnapshotOnEdt(deferSyntheticInvalidation = true)
            } finally {
                publicationScheduled.set(false)
                if (!project.isDisposed && publishedSnapshot.get() !== models.snapshot()) {
                    scheduleSnapshotPublication()
                }
            }
        }, ModalityState.defaultModalityState())
    }

    private fun publishLatestSnapshotOnEdt(deferSyntheticInvalidation: Boolean) {
        if (project.isDisposed) return
        val expected = publishedSnapshot.get()
        val next = models.snapshot()
        if (expected === next) return

        val expectedById = expected.workspaces.associateBy(EternalScriptWorkspace::id)
        val nextById = next.workspaces.associateBy(EternalScriptWorkspace::id)
        val workspaceIds = expectedById.keys + nextById.keys
        val syntheticChangedIds = workspaceIds.filterTo(linkedSetOf()) { id ->
            val old = expectedById[id]?.generatedFiles.orEmpty().map(::generatedSignature)
            val current = nextById[id]?.generatedFiles.orEmpty().map(::generatedSignature)
            old != current
        }
        val openFileRestartIds = workspaceIds.filterTo(linkedSetOf()) { id ->
            val old = expectedById[id]
            val current = nextById[id]
            id in syntheticChangedIds ||
                old?.configurationFingerprint != current?.configurationFingerprint ||
                old?.sourceUrls != current?.sourceUrls
        }

        // Cancel only highlighting for affected open scripts before requesting the K2 write-side
        // event. Queued model publications are collapsed into this latest snapshot, so a burst of
        // edits cannot enqueue one global write action per intermediate ABI.
        restartOpenFiles(expected, next, openFileRestartIds, "EternalScript model update")
        if (syntheticChangedIds.isNotEmpty()) {
            if (deferSyntheticInvalidation) {
                requestSyntheticModelInvalidation()
            } else {
                Idea262Facade.invalidateSyntheticScriptModel(project)
            }
        }
        configurations.snapshotPublished(expected, next)
        publishedSnapshot.set(next)
    }

    /**
     * Daemon cancellation and K2 model invalidation must not happen in the same EDT turn.
     * `daemon.restart` first cancels affected highlighting. Deferring the tiny write-side event
     * gives cancellable readers a chance to observe that cancellation before EDT requests the
     * application write lock. Multiple publications collapse into one pending invalidation.
     */
    private fun requestSyntheticModelInvalidation() {
        syntheticInvalidationRequested.set(true)
        if (project.isDisposed || !syntheticInvalidationScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            try {
                if (!project.isDisposed && syntheticInvalidationRequested.getAndSet(false)) {
                    Idea262Facade.invalidateSyntheticScriptModel(project)
                }
            } finally {
                syntheticInvalidationScheduled.set(false)
                if (!project.isDisposed && syntheticInvalidationRequested.get()) {
                    requestSyntheticModelInvalidation()
                }
            }
        }, ModalityState.defaultModalityState())
    }

    private fun restartOpenFiles(
        expected: EternalScriptProjectSnapshot,
        next: EternalScriptProjectSnapshot,
        changedWorkspaceIds: Set<String>,
        reason: Any
    ) {
        if (changedWorkspaceIds.isEmpty() || project.isDisposed) return
        val daemon = DaemonCodeAnalyzer.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        FileEditorManager.getInstance(project).openFiles.forEach { file ->
            val workspaceId = next.workspaceFor(file)?.id ?: expected.workspaceFor(file)?.id
            if (workspaceId !in changedWorkspaceIds) return@forEach
            val psi = psiManager.findFile(file) as? KtFile ?: return@forEach
            daemon.restart(psi, reason)
        }
    }

    private fun snapshotOf(
        workspaces: List<EternalScriptWorkspace>,
        version: Long
    ): EternalScriptProjectSnapshot {
        val digest = EternalScriptDeclarationRenderer.digest(buildString {
            workspaces.sortedBy(EternalScriptWorkspace::id).forEach { workspace -> appendLine(workspace.digest) }
        })
        return EternalScriptProjectSnapshot(workspaces, digest, version)
    }

    private fun enterManualTestAnalysis() {
        manualTestAnalysis.set(true)
        registryEpoch.incrementAndGet()
        workspaceEpochs.values.forEach(AtomicLong::incrementAndGet)
        discoveryAlarm.cancelAllRequests()
        workspaceAlarms.values.forEach(Alarm::cancelAllRequests)
        discoveryAlarm.waitForAllExecuted(10, TimeUnit.SECONDS)
        workspaceAlarms.values.forEach { alarm ->
            alarm.waitForAllExecuted(10, TimeUnit.SECONDS)
        }
    }

    private fun checkCanceled(epoch: Long) {
        com.intellij.openapi.progress.ProgressManager.checkCanceled()
        if (registryEpoch.get() != epoch || project.isDisposed) throw ProcessCanceledException()
    }

    private fun checkWorkspaceEpoch(workspaceId: String, epoch: Long) {
        com.intellij.openapi.progress.ProgressManager.checkCanceled()
        if (workspaceEpochs[workspaceId]?.get() != epoch || project.isDisposed) throw ProcessCanceledException()
    }

    private fun <T> drain(map: ConcurrentHashMap<String, MutableSet<T>>, key: String): Set<T> =
        map.remove(key)?.toSet().orEmpty()

    private fun restorePending(workspaceId: String, paths: Set<Path>, urls: Set<String>) {
        if (paths.isNotEmpty()) pendingPaths.computeIfAbsent(workspaceId) { ConcurrentHashMap.newKeySet() }.addAll(paths)
        if (urls.isNotEmpty()) pendingUrls.computeIfAbsent(workspaceId) { ConcurrentHashMap.newKeySet() }.addAll(urls)
    }

    private fun isEternalScript(file: VirtualFile): Boolean = file.name.endsWith(ETERNAL_SCRIPT_SUFFIX)

    private fun generatedSignature(file: EternalScriptGeneratedFile): String =
        "${file.fileName}\u0000${file.packageName.asString()}\u0000${file.textDigest}"

    companion object {
        const val ETERNAL_SCRIPT_SUFFIX: String = ".eternal.kts"
        private const val REFRESH_DELAY_MILLIS: Int = 300
        private const val STABILIZATION_DELAY_MILLIS: Int = 300
        private const val MAX_STABILIZATION_PASSES: Int = 4

        fun getInstance(project: Project): EternalScriptProjectService = project.service()
    }
}
