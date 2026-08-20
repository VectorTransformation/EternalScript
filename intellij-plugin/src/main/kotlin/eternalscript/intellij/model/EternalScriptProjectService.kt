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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.util.Alarm
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.intellij.analysis.AbiAnalyzer
import eternalscript.intellij.analysis.DeclarationEnvironmentIndex
import eternalscript.intellij.analysis.IndexedScriptFile
import eternalscript.intellij.analysis.ScriptFileIndex
import eternalscript.intellij.analysis.SyntheticModelPublisher
import eternalscript.intellij.diagnostics.EternalScriptIdeMetricsTracker
import eternalscript.intellij.diagnostics.EternalScriptProblemReporter
import eternalscript.intellij.refactoring.EternalScriptReferenceIndex
import eternalscript.intellij.resolve.Idea262Facade
import eternalscript.intellij.scripting.ScriptConfigurationCoordinator
import eternalscript.intellij.workspace.EternalScriptWorkspaceDescriptor
import eternalscript.intellij.workspace.WorkspaceRegistry
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class EternalScriptProjectService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(EternalScriptProjectService::class.java)
    private val snapshot = AtomicReference(EternalScriptProjectSnapshot.EMPTY)
    private val descriptors = AtomicReference<Map<String, EternalScriptWorkspaceDescriptor>>(emptyMap())
    private val started = AtomicBoolean()
    private val manualTestAnalysis = AtomicBoolean()
    private val publicationScheduled = AtomicBoolean()
    private val syntheticInvalidationRequested = AtomicBoolean()
    private val syntheticInvalidationScheduled = AtomicBoolean()
    private val publishedSnapshot = AtomicReference(EternalScriptProjectSnapshot.EMPTY)
    private val registryEpoch = AtomicLong()
    private val workspaceEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val discoveryAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val workspaceAlarms = ConcurrentHashMap<String, Alarm>()
    private val pendingPaths = ConcurrentHashMap<String, MutableSet<Path>>()
    private val pendingUrls = ConcurrentHashMap<String, MutableSet<String>>()
    private val stabilizationCounts = ConcurrentHashMap<String, MutableMap<String, Int>>()
    private val analysisProblems = ConcurrentHashMap<String, EternalScriptEnvironmentProblem.AnalysisUnstable>()
    private val testBasePath = AtomicReference<Path?>(null)
    private val metrics = EternalScriptIdeMetricsTracker()
    private val registry = WorkspaceRegistry(project)
    private val fileIndex = ScriptFileIndex()
    private val analyzer = AbiAnalyzer(project)
    private val references = EternalScriptReferenceIndex()
    private val queries = EternalScriptProjectQueries(project, snapshot::get, references)
    private val problems = EternalScriptProblemReporter(project)
    private val configurations = ScriptConfigurationCoordinator(
        project,
        metrics,
        snapshot::get
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

    fun current(): EternalScriptProjectSnapshot = snapshot.get()

    fun workspaceFor(file: VirtualFile): EternalScriptWorkspace? = queries.workspaceFor(file)

    fun definitionWorkspaces(): List<EternalScriptWorkspace> = current().workspaces

    fun definitionConfiguration(workspace: EternalScriptWorkspace): ScriptCompilationConfiguration =
        configurations.definitionConfiguration(workspace)

    fun scriptDefinitionRegistered() = configurations.definitionRegistered()

    @TestOnly
    fun setScriptConfigurationReloadsEnabledForTests(enabled: Boolean) =
        configurations.setReloadsEnabledForTests(enabled)

    fun conflictFor(element: PsiElement): EternalScriptConflict? = queries.conflictFor(element)

    fun sourceDeclaration(element: PsiElement): KtNamedDeclaration? = queries.sourceDeclaration(element)

    fun findReferences(
        targetElement: PsiElement,
        scope: SearchScope? = null
    ): List<PsiReference> = queries.findReferences(targetElement, scope)

    fun renameConflicts(targetElement: PsiElement, newName: String): List<EternalScriptConflict> =
        queries.renameConflicts(targetElement, newName)

    fun scheduleDiscovery(delay: Int = REFRESH_DELAY_MILLIS) {
        if (project.isDisposed || manualTestAnalysis.get()) return
        val epoch = registryEpoch.incrementAndGet()
        discoveryAlarm.cancelAllRequests()
        discoveryAlarm.addRequest({ performDiscovery(epoch, testBasePath.get(), synchronous = false) }, delay)
    }

    fun diagnosticsText(): String = buildString {
        val current = current()
        appendLine("EternalScript IntelliJ plugin 2.1.3")
        appendLine("protocol=${IdeProtocol.VERSION}; snapshot=${current.version}; digest=${current.digest}")
        current.workspaces.forEach { workspace ->
            appendLine("workspace=${workspace.id}")
            appendLine("manifest=${workspace.manifest}")
            appendLine("environmentHash=${workspace.manifestDigest}")
            appendLine("scriptRoot=${workspace.scriptRoot}")
            appendLine("runtime=${workspace.environment.runtimePluginVersion()}; kotlin=${workspace.environment.kotlinVersion()}")
            appendLine("sources=${workspace.sourceUrls.size}; active=${workspace.activeSourceUrls.size}")
        }
        appendLine("metrics=${metrics.snapshot()}")
        current.problems.forEach { problem -> appendLine("problem=$problem") }
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
    fun metricsForTests(): EternalScriptIdeMetrics = metrics.snapshot()

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
    @KaAllowAnalysisOnEdt
    fun analyzePathSynchronouslyForTests(path: Path) {
        check(ApplicationManager.getApplication().isUnitTestMode)
        enterManualTestAnalysis()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val normalized = path.toAbsolutePath().normalize()
        val descriptor = registry.nearest(descriptors.get().values, normalized)
            ?: error("No EternalScript workspace owns $normalized")
        pendingPaths.computeIfAbsent(descriptor.id) { ConcurrentHashMap.newKeySet() }.add(normalized)
        metrics.changedFiles(1)
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
        if (project.isDisposed || registryEpoch.get() != epoch) return
        val startedAt = System.nanoTime()
        try {
            val base = baseOverride ?: project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
                ?: return
            val manifests = if (baseOverride != null) {
                registry.diskManifestPaths(base)
            } else {
                ReadAction.computeCancellable<List<Path>, RuntimeException> { registry.indexedManifestPaths(base) }
            }
            val registrySnapshot = registry.load(manifests)
            if (registryEpoch.get() != epoch) return
            val previousSnapshot = current()
            val previous = previousSnapshot.workspaces.associateBy(EternalScriptWorkspace::id)
            val descriptorList = registrySnapshot.descriptors
            val discoveredByWorkspace = linkedMapOf<String, Map<String, IndexedScriptFile>>()
            val nextWorkspaces = descriptorList.mapNotNull { descriptor ->
                metrics.workspaceScan()
                val discovered = fileIndex.enumerate(descriptor).filterValues { file ->
                    registry.nearest(descriptorList, file.path)?.id == descriptor.id
                }
                discoveredByWorkspace[descriptor.id] = discovered
                val oldWorkspace = previous[descriptor.id]
                val oldAbis = oldWorkspace?.fileAbis.orEmpty()
                val abis = linkedMapOf<String, EternalScriptFileAbi>()
                val activeUrls = discovered.values.asSequence()
                    .filter(IndexedScriptFile::active)
                    .mapTo(linkedSetOf(), IndexedScriptFile::url)
                // A disabled file is analyzed against runtime-visible exports without becoming one.
                val declarationEnvironment = DeclarationEnvironmentIndex(oldAbis, activeUrls)
                discovered.values.forEach { file ->
                    checkCanceled(epoch)
                    analyzer.analyze(
                        file,
                        oldAbis[file.url],
                        declarationEnvironment.excluding(file.url)
                    )?.let { abi ->
                        metrics.abiAnalysis()
                        abis[file.url] = abi
                        declarationEnvironment.replace(file.url, abi)
                    }
                }
                SyntheticModelPublisher.build(descriptor, discovered, abis, previous[descriptor.id]).workspace
            }
            if (registryEpoch.get() != epoch || project.isDisposed) return
            metrics.analysisFinished((System.nanoTime() - startedAt) / 1_000_000)
            val next = snapshotOf(
                nextWorkspaces,
                registrySnapshot.problems +
                    (if (baseOverride != null && manifests.isEmpty()) listOf(EternalScriptEnvironmentProblem.Missing()) else emptyList()) +
                    analysisProblems.values,
                previousSnapshot.version + 1
            )
            if (publishSnapshot(previousSnapshot, next, synchronous)) {
                val acceptedDescriptors = descriptorList.associateBy(EternalScriptWorkspaceDescriptor::id)
                descriptors.set(acceptedDescriptors)
                discoveredByWorkspace.forEach(fileIndex::replace)
                fileIndex.removeMissingWorkspaces(acceptedDescriptors.keys)
                workspaceEpochs.keys.removeIf { id -> id !in acceptedDescriptors }
                analysisProblems.keys.removeIf { id -> id !in acceptedDescriptors }
            } else if (!project.isDisposed) {
                scheduleDiscovery(delay = 0)
            }
        } catch (cancelled: ProcessCanceledException) {
            metrics.cancellation()
            if (shouldRetryDiscoveryAfterCancellation(synchronous, project.isDisposed, epoch, registryEpoch.get())) {
                scheduleDiscovery()
            }
            throw cancelled
        } catch (error: Throwable) {
            log.warn("EternalScript workspace discovery failed", error)
            problems.reportAnalysisFailure(error.message ?: error.javaClass.name, error)
            if (synchronous) throw error
        } finally {
            metrics.analysisFinished((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun shouldRetryDiscoveryAfterCancellation(
        synchronous: Boolean,
        projectDisposed: Boolean,
        attemptedEpoch: Long,
        currentEpoch: Long
    ): Boolean = !synchronous && !projectDisposed && attemptedEpoch == currentEpoch

    private fun markChanged(path: Path, waitForCommit: Boolean) {
        val descriptor = registry.nearest(descriptors.get().values, path.toAbsolutePath().normalize()) ?: return
        stabilizationCounts.remove(descriptor.id)
        pendingPaths.computeIfAbsent(descriptor.id) { ConcurrentHashMap.newKeySet() }.add(path.toAbsolutePath().normalize())
        metrics.changedFiles(1)
        scheduleWorkspace(descriptor.id, waitForCommit, REFRESH_DELAY_MILLIS)
    }

    private fun scheduleAffected(workspaceId: String, urls: Collection<String>) {
        if (urls.isEmpty()) {
            analysisProblems.remove(workspaceId)
            return
        }
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
        if (rejected.isNotEmpty()) {
            descriptors.get()[workspaceId]?.let { descriptor ->
                analysisProblems[workspaceId] = EternalScriptEnvironmentProblem.AnalysisUnstable(
                    descriptor.manifest,
                    rejected
                )
            }
        }
        if (accepted.isEmpty()) {
            republishProblems()
            return
        }
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
        if (project.isDisposed || workspaceEpochs[workspaceId]?.get() != epoch) return
        val startedAt = System.nanoTime()
        val drainedPaths = drain(pendingPaths, workspaceId)
        val drainedUrls = drain(pendingUrls, workspaceId)
        try {
            val descriptor = descriptors.get()[workspaceId] ?: return
            val previousSnapshot = current()
            val previous = previousSnapshot.workspaces.firstOrNull { workspace -> workspace.id == workspaceId }
                ?: return
            val oldFiles = fileIndex.files(workspaceId)
            val dirtyUrls = linkedSetOf<String>()
            drainedPaths.forEach { path ->
                oldFiles.values.firstOrNull { file -> file.path == path }?.url?.let(dirtyUrls::add)
                fileIndex.update(descriptor, path)?.url?.let(dirtyUrls::add)
            }
            dirtyUrls += drainedUrls
            val files = fileIndex.files(workspaceId)
            val abis = previous.fileAbis.toMutableMap()
            abis.keys.retainAll(files.keys)
            val activeUrls = files.values.asSequence()
                .filter(IndexedScriptFile::active)
                .mapTo(linkedSetOf(), IndexedScriptFile::url)
            val declarationEnvironment = DeclarationEnvironmentIndex(abis, activeUrls)
            dirtyUrls.forEach { url ->
                checkWorkspaceEpoch(workspaceId, epoch)
                val file = files[url] ?: return@forEach
                analyzer.analyze(
                    file,
                    abis[url],
                    declarationEnvironment.excluding(file.url)
                )?.let { abi ->
                    metrics.abiAnalysis()
                    abis[url] = abi
                    declarationEnvironment.replace(url, abi)
                }
            }
            val published = SyntheticModelPublisher.build(descriptor, files, abis, previous)
            checkWorkspaceEpoch(workspaceId, epoch)
            val nextWorkspaces = previousSnapshot.workspaces.map { workspace ->
                if (workspace.id == workspaceId) published.workspace else workspace
            }
            metrics.analysisFinished((System.nanoTime() - startedAt) / 1_000_000)
            val next = snapshotOf(
                nextWorkspaces,
                previousSnapshot.problems.filterNot { problem -> problem is EternalScriptEnvironmentProblem.AnalysisUnstable } +
                    analysisProblems.values,
                previousSnapshot.version + 1
            )
            val accepted = publishSnapshot(previousSnapshot, next, synchronous)
            if (!accepted) {
                restorePending(workspaceId, drainedPaths, drainedUrls)
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
        } catch (cancelled: ProcessCanceledException) {
            metrics.cancellation()
            restorePending(workspaceId, drainedPaths, drainedUrls)
            throw cancelled
        } catch (error: Throwable) {
            restorePending(workspaceId, drainedPaths, drainedUrls)
            log.warn("EternalScript incremental analysis failed for $workspaceId", error)
            problems.reportAnalysisFailure(error.message ?: error.javaClass.name, error)
            if (synchronous) throw error
        } finally {
            metrics.analysisFinished((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun publishSnapshot(
        expected: EternalScriptProjectSnapshot,
        next: EternalScriptProjectSnapshot,
        synchronous: Boolean
    ): Boolean {
        configurations.prepareSnapshotPublication(expected, next)
        if (!snapshot.compareAndSet(expected, next)) return false
        references.publish(next)
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
                if (!project.isDisposed && publishedSnapshot.get() !== snapshot.get()) {
                    scheduleSnapshotPublication()
                }
            }
        }, ModalityState.defaultModalityState())
    }

    private fun publishLatestSnapshotOnEdt(deferSyntheticInvalidation: Boolean) {
        if (project.isDisposed) return
        val expected = publishedSnapshot.get()
        val next = snapshot.get()
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
        problems.publish(next.problems)
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

    private fun republishProblems() {
        while (true) {
            val previous = current()
            val next = snapshotOf(
                previous.workspaces,
                previous.problems.filterNot { problem -> problem is EternalScriptEnvironmentProblem.AnalysisUnstable } +
                    analysisProblems.values,
                previous.version + 1
            )
            if (publishSnapshot(previous, next, synchronous = false)) return
        }
    }

    private fun snapshotOf(
        workspaces: List<EternalScriptWorkspace>,
        problems: List<EternalScriptEnvironmentProblem>,
        version: Long
    ): EternalScriptProjectSnapshot {
        val digest = EternalScriptDeclarationRenderer.digest(buildString {
            workspaces.sortedBy(EternalScriptWorkspace::id).forEach { workspace -> appendLine(workspace.digest) }
            problems.sortedBy { problem -> problem.toString() }.forEach { problem -> appendLine(problem) }
        })
        return EternalScriptProjectSnapshot(workspaces, problems, digest, version, metrics.snapshot())
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
