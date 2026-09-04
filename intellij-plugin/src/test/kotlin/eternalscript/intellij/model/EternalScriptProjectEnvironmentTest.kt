package eternalscript.intellij.model

import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.util.concurrency.AppExecutorUtil
import eternalscript.ide.protocol.IdeEnvironment
import eternalscript.ide.protocol.IdeEnvironmentCodec
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.intellij.resolve.Idea262Facade
import eternalscript.intellij.workspace.WorkspaceRegistry
import org.jetbrains.kotlin.idea.core.script.k2.ReloadScriptConfigurationService
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@KaAllowAnalysisOnEdt
internal class EternalScriptProjectEnvironmentTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    fun testMissingDamagedAndValidEnvironmentStates() {
        val base = Path.of(myFixture.tempDirPath)
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        myFixture.addFileToProject("scripts/test.eternal.kts", "val value: Int = 1")

        model.rebuildSynchronouslyForTests(base)
        assertTrue(model.current().workspaces.isEmpty())

        val manifest = base.resolve(IdeProtocol.ENVIRONMENT_FILE)
        Files.createDirectories(manifest.parent)
        Files.writeString(manifest, "not-an-environment")
        VirtualFileManager.getInstance().syncRefresh()
        model.rebuildSynchronouslyForTests(base)
        assertTrue(model.current().workspaces.isEmpty())

        Files.write(manifest, environment(base))
        VirtualFileManager.getInstance().syncRefresh()
        model.rebuildSynchronouslyForTests(base)
        assertEquals("fixture-${base.fileName}", model.current().workspaces.single().environment.environmentFingerprint())
    }

    fun testMultipleWorkspacesKeepSourceDeclarationsIsolated() {
        val base = Path.of(myFixture.tempDirPath)
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        val firstRoot = base.resolve("first/scripts")
        val secondRoot = base.resolve("second/scripts")
        myFixture.addFileToProject("first/scripts/first.eternal.kts", "val onlyFirst: Int = 1")
        myFixture.addFileToProject("second/scripts/second.eternal.kts", "val onlySecond: Int = 2")
        writeEnvironment(base.resolve("first"))
        writeEnvironment(base.resolve("second"))
        VirtualFileManager.getInstance().syncRefresh()

        model.rebuildSynchronouslyForTests(base)
        val workspaces = model.current().workspaces

        assertEquals(2, workspaces.size)
        assertEquals(2, workspaces.map(EternalScriptWorkspace::id).toSet().size)
        val first = workspaces.single { workspace -> workspace.scriptRoot == firstRoot }
        val second = workspaces.single { workspace -> workspace.scriptRoot == secondRoot }
        assertTrue("onlyFirst" in first.generatedText)
        assertTrue("onlySecond" !in first.generatedText)
        assertTrue("onlySecond" in second.generatedText)
        assertTrue("onlyFirst" !in second.generatedText)
    }

    fun testNestedWorkspaceUsesNearestScriptRootOnly() {
        val base = Path.of(myFixture.tempDirPath)
        val outer = base.resolve("outer")
        val nested = outer.resolve("scripts/nested")
        val outerFile = myFixture.addFileToProject(
            "outer/scripts/outer.eternal.kts",
            "val outerValue: Int = 1"
        )
        val nestedFile = myFixture.addFileToProject(
            "outer/scripts/nested/scripts/nested.eternal.kts",
            "val nestedValue: Int = 2"
        )
        writeEnvironment(outer)
        writeEnvironment(nested)
        VirtualFileManager.getInstance().syncRefresh()
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)

        model.rebuildSynchronouslyForTests(base)

        val outerWorkspace = requireNotNull(model.current().workspaceFor(outerFile.virtualFile))
        val nestedWorkspace = requireNotNull(model.current().workspaceFor(nestedFile.virtualFile))
        assertTrue(outerWorkspace.id != nestedWorkspace.id)
        assertTrue(outerFile.virtualFile.url in outerWorkspace.sourceUrls)
        assertFalse(nestedFile.virtualFile.url in outerWorkspace.sourceUrls)
        assertTrue(nestedFile.virtualFile.url in nestedWorkspace.sourceUrls)
    }

    fun testSourceChangeBetweenDiscoveryAnalysisAndDescriptorPublicationIsRehomedToNewNestedWorkspace() {
        val base = Path.of(myFixture.tempDirPath)
        val outer = base.resolve("outer")
        val nested = outer.resolve("scripts/nested")
        val outerFile = myFixture.addFileToProject(
            "outer/scripts/outer.eternal.kts",
            "val outerValue: Int = 1"
        ).virtualFile
        val nestedFile = myFixture.addFileToProject(
            "outer/scripts/nested/scripts/nested.eternal.kts",
            "val staleNestedValue: Int = 1"
        ).virtualFile
        writeEnvironment(outer)
        VirtualFileManager.getInstance().syncRefresh()
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        model.rebuildSynchronouslyForTests(base)
        val outerId = model.current().workspaces.single().id

        writeEnvironment(nested)
        VirtualFileManager.getInstance().syncRefresh()
        model.setBeforeDescriptorPublicationForTests {
            Files.writeString(nestedFile.toNioPath(), "val freshNestedValue: String = \"fresh\"")
            nestedFile.refresh(false, false)
            // This deliberately resolves through the old outer-only descriptor map.
            model.markChangedForTests(nestedFile.toNioPath())
        }
        try {
            model.rebuildSynchronouslyForTests(base)
        } finally {
            model.setBeforeDescriptorPublicationForTests(null)
        }

        val afterDiscovery = model.current()
        val outerWorkspace = afterDiscovery.workspaces.single { it.id == outerId }
        val nestedWorkspace = afterDiscovery.workspaces.single { it.id != outerId }
        assertTrue(outerFile.url in outerWorkspace.sourceUrls)
        assertFalse(nestedFile.url in outerWorkspace.sourceUrls)
        assertTrue(nestedFile.url in nestedWorkspace.sourceUrls)
        assertEquals(nestedWorkspace.id, model.pendingWorkspaceForPathForTests(nestedFile.toNioPath()))

        model.analyzePathSynchronouslyForTests(nestedFile.toNioPath())

        val afterReplay = model.current()
        val replayedOuter = afterReplay.workspaces.single { it.id == outerId }
        val replayedNested = afterReplay.workspaces.single { it.id == nestedWorkspace.id }
        assertFalse(nestedFile.url in replayedOuter.sourceUrls)
        assertFalse(replayedOuter.generatedText.contains("freshNestedValue"))
        assertContains(replayedNested.generatedText, "freshNestedValue")
    }

    fun testSourceChangeWithoutAnOldOwnerRequestsRediscoveryAfterDescriptorPublication() {
        val base = Path.of(myFixture.tempDirPath)
        val script = myFixture.addFileToProject(
            "scripts/provider.eternal.kts",
            "val staleValue: Int = 1"
        ).virtualFile
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        model.rebuildSynchronouslyForTests(base)
        assertTrue(model.current().workspaces.isEmpty())

        writeEnvironment(base)
        VirtualFileManager.getInstance().syncRefresh()
        val requestsBefore = model.sourceChangeRediscoveryRequestsForTests()
        model.setBeforeDescriptorPublicationForTests {
            Files.writeString(script.toNioPath(), "val freshValue: String = \"fresh\"")
            script.refresh(false, false)
            // There is no old owner yet, so this would have been silently dropped without
            // the global source-change epoch replay.
            model.markChangedForTests(script.toNioPath())
        }
        try {
            model.rebuildSynchronouslyForTests(base)
        } finally {
            model.setBeforeDescriptorPublicationForTests(null)
        }

        assertEquals(requestsBefore + 1, model.sourceChangeRediscoveryRequestsForTests())
        assertFalse(model.current().workspaces.single().generatedText.contains("freshValue"))

        // Synchronous test mode intentionally does not run the queued alarm; replay its next
        // discovery deterministically and verify that the event's ABI is no longer stale.
        model.rebuildSynchronouslyForTests(base)
        assertContains(model.current().workspaces.single().generatedText, "freshValue")
    }

    fun testRejectsUntrustedAndMissingClasspathEnvironments() {
        val base = Path.of(myFixture.tempDirPath)
        val untrustedRoot = base.resolve("untrusted")
        Files.createDirectories(untrustedRoot.resolve("scripts"))
        writeEnvironment(untrustedRoot)
        val untrustedManifest = untrustedRoot.resolve(IdeProtocol.ENVIRONMENT_FILE)
        val untrusted = WorkspaceRegistry(project) { false }.load(listOf(untrustedManifest))
        assertTrue(untrusted.isEmpty())

        val missingRoot = base.resolve("missing-classpath")
        Files.createDirectories(missingRoot.resolve("scripts"))
        val missingManifest = missingRoot.resolve(IdeProtocol.ENVIRONMENT_FILE)
        Files.createDirectories(missingManifest.parent)
        Files.write(
            missingManifest,
            environment(missingRoot, listOf(missingRoot.resolve("absent.jar").toUri()))
        )
        val missing = WorkspaceRegistry(project).load(listOf(missingManifest))
        assertTrue(missing.isEmpty())

    }

    fun testOneChangedPsiScriptReanalyzesWithoutRescan() {
        val base = Path.of(myFixture.tempDirPath)
        val scriptRoot = base.resolve("scripts")
        Files.createDirectories(scriptRoot)
        repeat(3) { index ->
            Files.writeString(scriptRoot.resolve("script-$index.eternal.kts"), "val value$index: Int = $index")
        }
        writeEnvironment(base)
        VirtualFileManager.getInstance().syncRefresh()
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        model.rebuildSynchronouslyForTests(base)
        val beforeWorkspace = model.current().workspaces.single()
        val scans = model.indexedScanCountForTests()
        val changed = scriptRoot.resolve("script-1.eternal.kts")

        Files.writeString(changed, "val value1: Int = 2")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(changed)?.refresh(false, false)
        model.analyzePathSynchronouslyForTests(changed)

        assertEquals(scans, model.indexedScanCountForTests())
        val afterWorkspace = model.current().workspaces.single()
        assertSame(beforeWorkspace.generatedFiles, afterWorkspace.generatedFiles)
        assertTrue(afterWorkspace.pendingInvalidatedNames.isEmpty())
        assertEquals(beforeWorkspace.configurationFingerprint, afterWorkspace.configurationFingerprint)
        assertEquals(beforeWorkspace.digest, afterWorkspace.digest)
        assertNotEquals(
            beforeWorkspace.fileAbis.getValue(beforeWorkspace.sourceUrls.single { it.endsWith("script-1.eternal.kts") })
                .contentDigest,
            afterWorkspace.fileAbis.getValue(afterWorkspace.sourceUrls.single { it.endsWith("script-1.eternal.kts") })
                .contentDigest
        )
    }

    fun testAbiChangeRebuildsSyntheticModel() {
        val base = Path.of(myFixture.tempDirPath)
        val script = myFixture.addFileToProject(
            "scripts/provider.eternal.kts",
            "val sharedValue: Int = 1"
        ).virtualFile
        writeEnvironment(base)
        VirtualFileManager.getInstance().syncRefresh()
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        model.rebuildSynchronouslyForTests(base)
        val before = model.current().workspaces.single()

        Files.writeString(script.toNioPath(), "val sharedValue: String = \"changed\"")
        script.refresh(false, false)
        model.analyzePathSynchronouslyForTests(script.toNioPath())

        val after = model.current().workspaces.single()
        val beforeDeclarations = before.generatedFiles.single()
        val afterDeclarations = after.generatedFiles.single()
        assertTrue(beforeDeclarations.fileName.startsWith("EternalScriptShared_"))
        assertTrue(afterDeclarations.fileName.startsWith("EternalScriptShared_"))
        assertNotSame(beforeDeclarations, afterDeclarations)
        assertNotEquals(beforeDeclarations.textDigest, afterDeclarations.textDigest)
        assertContains(afterDeclarations.text, "val sharedValue: kotlin.String")
    }

    fun testRemovedWorkspaceRetiresQueuedAnalysisState() {
        val base = Path.of(myFixture.tempDirPath)
        val script = myFixture.addFileToProject(
            "scripts/provider.eternal.kts",
            "val sharedValue: Int = 1"
        ).virtualFile
        writeEnvironment(base)
        VirtualFileManager.getInstance().syncRefresh()
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        model.rebuildSynchronouslyForTests(base)
        val workspaceId = model.current().workspaces.single().id

        model.seedWorkspaceWorkStateForTests(workspaceId, script.toNioPath(), script.url)
        assertTrue(model.hasWorkspaceWorkStateForTests(workspaceId))

        val analysisEntered = CountDownLatch(1)
        val releaseAnalysis = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val analysis = Thread {
            model.withWorkspaceAnalysisGateForTests(workspaceId) {
                analysisEntered.countDown()
                check(releaseAnalysis.await(30, TimeUnit.SECONDS))
                model.seedWorkspaceWorkStateForTests(workspaceId, script.toNioPath(), script.url)
            }
        }
        val cleanup = Thread {
            cleanupStarted.countDown()
            model.retireWorkspaceWorkStateForTests(emptySet())
        }

        analysis.start()
        assertTrue(analysisEntered.await(30, TimeUnit.SECONDS))
        cleanup.start()
        assertTrue(cleanupStarted.await(30, TimeUnit.SECONDS))
        try {
            waitForBlocked(cleanup)
        } finally {
            releaseAnalysis.countDown()
            analysis.join(30_000)
            cleanup.join(30_000)
        }

        assertFalse(analysis.isAlive)
        assertFalse(cleanup.isAlive)
        assertFalse(model.hasWorkspaceWorkStateForTests(workspaceId))
    }

    fun testDisabledDeclarationsRemainInTheIdeModelAcrossRuntimeActiveStateChanges() {
        val base = Path.of(myFixture.tempDirPath)
        myFixture.addFileToProject(
            "scripts/active.eternal.kts",
            "val activeValue: Int = 1"
        )
        val disabled = myFixture.addFileToProject(
            "scripts/-provider.eternal.kts",
            "val disabledValue: Int = 2"
        ).virtualFile
        writeEnvironment(base)
        VirtualFileManager.getInstance().syncRefresh()
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)

        model.rebuildSynchronouslyForTests(base)
        val before = model.current().workspaces.single()
        assertTrue(disabled.url in before.sourceUrls)
        assertContains(before.generatedText, "activeValue")
        assertContains(before.generatedText, "disabledValue")

        Files.writeString(disabled.toNioPath(), "val changedDisabledValue: String = \"changed\"")
        disabled.refresh(false, false)
        model.analyzePathSynchronouslyForTests(disabled.toNioPath())

        val afterDisabledEdit = model.current().workspaces.single()
        assertNotSame(before.generatedFiles.single(), afterDisabledEdit.generatedFiles.single())
        assertContains(afterDisabledEdit.pendingInvalidatedNames, "disabledValue")
        assertContains(afterDisabledEdit.pendingInvalidatedNames, "changedDisabledValue")
        assertContains(afterDisabledEdit.generatedText, "changedDisabledValue")

        WriteCommandAction.runWriteCommandAction(project) {
            disabled.rename(this, "provider.eternal.kts")
        }
        model.rebuildSynchronouslyForTests(base)

        val enabled = model.current().workspaces.single()
        assertTrue(disabled.url in enabled.sourceUrls)
        assertContains(enabled.generatedText, "changedDisabledValue")

        WriteCommandAction.runWriteCommandAction(project) {
            disabled.rename(this, "-provider.eternal.kts")
        }
        model.rebuildSynchronouslyForTests(base)

        val disabledAgain = model.current().workspaces.single()
        assertTrue(disabled.url in disabledAgain.sourceUrls)
        assertContains(disabledAgain.generatedText, "changedDisabledValue")
    }

    fun testVfsRenameMoveCopyAndDirectoryDeleteRequestWorkspaceRediscovery() {
        val script = myFixture.addFileToProject("scripts/provider.eternal.kts", "val value: Int = 1").virtualFile
        val destination = myFixture.tempDirFixture.findOrCreateDir("destination")
        val structuralEvents = listOf(
            VFilePropertyChangeEvent(
                this,
                script,
                VirtualFile.PROP_NAME,
                script.name,
                "renamed.eternal.kts",
                false
            ),
            VFileMoveEvent(this, script, destination),
            VFileCopyEvent(this, script, destination, "copied.eternal.kts"),
            VFileDeleteEvent(this, requireNotNull(script.parent), false)
        )

        structuralEvents.forEach { event ->
            val plan = EternalScriptVfsChangePlanner.plan(listOf(event))
            assertTrue(plan.rediscover, event.toString())
            assertTrue(plan.changedPaths.isEmpty(), event.toString())
        }

        val ordinaryFileEvent = VFilePropertyChangeEvent(
            this,
            script,
            VirtualFile.PROP_WRITABLE,
            true,
            false,
            false
        )
        val incremental = EternalScriptVfsChangePlanner.plan(listOf(ordinaryFileEvent))
        assertFalse(incremental.rediscover)
        assertEquals(
            setOf(script.toNioPath().toAbsolutePath().normalize()),
            incremental.changedPaths
        )
    }

    fun testWorkspaceRediscoveryReplacesTheOldUrlAfterScriptRename() {
        val base = Path.of(myFixture.tempDirPath)
        val script = myFixture.addFileToProject(
            "scripts/provider.eternal.kts",
            "val sharedValue: Int = 1"
        ).virtualFile
        writeEnvironment(base)
        VirtualFileManager.getInstance().syncRefresh()
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        model.rebuildSynchronouslyForTests(base)
        val oldUrl = script.url

        WriteCommandAction.runWriteCommandAction(project) {
            script.rename(this, "renamed.eternal.kts")
        }
        model.rebuildSynchronouslyForTests(base)

        val workspace = model.current().workspaces.single()
        assertFalse(oldUrl in workspace.sourceUrls)
        assertEquals(setOf(script.url), workspace.sourceUrls)
        assertContains(workspace.generatedText, "sharedValue")
    }

    fun testCancelledCurrentDiscoveryIsRetriedButStaleOrSynchronousDiscoveryIsNot() {
        val model = EternalScriptProjectService.getInstance(project)

        assertTrue(model.shouldRetryDiscoveryAfterCancellationForTests(false, false, 7, 7))
        assertFalse(model.shouldRetryDiscoveryAfterCancellationForTests(false, false, 7, 8))
        assertFalse(model.shouldRetryDiscoveryAfterCancellationForTests(true, false, 7, 7))
        assertFalse(model.shouldRetryDiscoveryAfterCancellationForTests(false, true, 7, 7))
    }

    fun testScriptConfigurationNotificationCanArriveWithoutReadAccess() {
        val base = Path.of(myFixture.tempDirPath)
        val script = myFixture.addFileToProject("scripts/test.eternal.kts", "val value: Int = 1")
        writeEnvironment(base)
        VirtualFileManager.getInstance().syncRefresh()
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        model.rebuildSynchronouslyForTests(base)

        val result = AppExecutorUtil.getAppExecutorService().submit<Boolean> {
            check(!ApplicationManager.getApplication().isReadAccessAllowed)
            model.scriptConfigurationChangedForTests(script.virtualFile)
        }.get(10, TimeUnit.SECONDS)

        assertFalse(result)
    }

    fun testSilentScriptConfigurationReloadDoesNotCreateKotlinNotifications() {
        val script = myFixture.addFileToProject(
            "scripts/notification-free.eternal.kts",
            "val value: Int = 1"
        ) as KtFile
        val completed = AtomicBoolean()
        val listenerDisposable = Disposer.newDisposable()
        ApplicationManager.getApplication().messageBus.connect(listenerDisposable).subscribe(
            ReloadScriptConfigurationService.TOPIC,
            object : ReloadScriptConfigurationService.Listener {
                override fun onNotificationChanged(virtualFile: com.intellij.openapi.vfs.VirtualFile) {
                    if (virtualFile == script.virtualFile) completed.set(true)
                }
            }
        )
        val before = kotlinScriptNotificationCount(script.name)

        try {
            assertTrue(Idea262Facade.reloadScriptConfigurationSilently(script))
            PlatformTestUtil.waitWithEventsDispatching(
                "Silent script configuration reload did not complete",
                completed::get,
                30
            )
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(before, kotlinScriptNotificationCount(script.name))
        } finally {
            Disposer.dispose(listenerDisposable)
        }
    }

    private fun waitForBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (thread.state != Thread.State.BLOCKED && thread.isAlive && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    private fun kotlinScriptNotificationCount(fileName: String): Int =
        NotificationsManager.getNotificationsManager()
            .getNotificationsOfType(Notification::class.java, project)
            .count { notification ->
                notification.groupId == "KotlinScriptNotificationGroup" &&
                    fileName in notification.content
            }

    private fun writeEnvironment(workspace: Path) {
        val manifest = workspace.resolve(IdeProtocol.ENVIRONMENT_FILE)
        Files.createDirectories(manifest.parent)
        Files.write(manifest, environment(workspace))
    }

    private fun environment(
        workspace: Path,
        classpath: List<java.net.URI> = emptyList()
    ): ByteArray = IdeEnvironmentCodec.encode(
        IdeEnvironment(
            UUID.nameUUIDFromBytes(workspace.toString().toByteArray()).toString(),
            "fixture-${workspace.fileName}",
            "scripts",
            classpath
        )
    )
}
