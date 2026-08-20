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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@KaAllowAnalysisOnEdt
internal class EternalScriptProjectEnvironmentTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    fun testMissingDamagedAndIncompatibleEnvironmentStates() {
        val base = Path.of(myFixture.tempDirPath)
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        myFixture.addFileToProject("scripts/test.eternal.kts", "val value: Int = 1")

        model.rebuildSynchronouslyForTests(base)
        assertIs<EternalScriptEnvironmentProblem.Missing>(model.current().problems.single())

        val manifest = base.resolve(IdeProtocol.ENVIRONMENT_FILE)
        Files.createDirectories(manifest.parent)
        Files.writeString(manifest, "not-an-environment")
        VirtualFileManager.getInstance().syncRefresh()
        model.rebuildSynchronouslyForTests(base)
        assertIs<EternalScriptEnvironmentProblem.Invalid>(model.current().problems.single())

        Files.write(manifest, environment(base, IdeProtocol.VERSION + 1))
        VirtualFileManager.getInstance().syncRefresh()
        model.rebuildSynchronouslyForTests(base)
        val incompatible = assertIs<EternalScriptEnvironmentProblem.Incompatible>(model.current().problems.single())
        assertEquals(IdeProtocol.VERSION + 1, incompatible.actual)
        assertEquals(IdeProtocol.VERSION, incompatible.expected)
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

    fun testRejectsUntrustedMissingClasspathAndIncompatibleKotlinEnvironments() {
        val base = Path.of(myFixture.tempDirPath)
        val untrustedRoot = base.resolve("untrusted")
        Files.createDirectories(untrustedRoot.resolve("scripts"))
        writeEnvironment(untrustedRoot)
        val untrustedManifest = untrustedRoot.resolve(IdeProtocol.ENVIRONMENT_FILE)
        val untrusted = WorkspaceRegistry(project) { false }.load(listOf(untrustedManifest))
        assertTrue(untrusted.descriptors.isEmpty())
        assertIs<EternalScriptEnvironmentProblem.Untrusted>(untrusted.problems.single())

        val missingRoot = base.resolve("missing-classpath")
        Files.createDirectories(missingRoot.resolve("scripts"))
        val missingManifest = missingRoot.resolve(IdeProtocol.ENVIRONMENT_FILE)
        Files.createDirectories(missingManifest.parent)
        Files.write(
            missingManifest,
            environment(missingRoot, IdeProtocol.VERSION, listOf(missingRoot.resolve("absent.jar").toUri()))
        )
        val missing = WorkspaceRegistry(project).load(listOf(missingManifest))
        assertTrue(missing.descriptors.isEmpty())
        assertIs<EternalScriptEnvironmentProblem.MissingClasspath>(missing.problems.single())

        val kotlinRoot = base.resolve("wrong-kotlin")
        Files.createDirectories(kotlinRoot.resolve("scripts"))
        val kotlinManifest = kotlinRoot.resolve(IdeProtocol.ENVIRONMENT_FILE)
        Files.createDirectories(kotlinManifest.parent)
        Files.write(kotlinManifest, environment(kotlinRoot, IdeProtocol.VERSION, kotlinVersion = "2.5.0"))
        val incompatible = WorkspaceRegistry(project).load(listOf(kotlinManifest))
        assertTrue(incompatible.descriptors.isEmpty())
        assertIs<EternalScriptEnvironmentProblem.IncompatibleKotlin>(incompatible.problems.single())
    }

    fun testOneOfOneThousandPsiScriptsReanalyzesWithoutRescanOrConfigurationReload() {
        val base = Path.of(myFixture.tempDirPath)
        val scriptRoot = base.resolve("scripts")
        Files.createDirectories(scriptRoot)
        repeat(1_000) { index ->
            Files.writeString(scriptRoot.resolve("script-$index.eternal.kts"), "val value$index: Int = $index")
        }
        writeEnvironment(base)
        VirtualFileManager.getInstance().syncRefresh()
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        model.rebuildSynchronouslyForTests(base)
        val beforeWorkspace = model.current().workspaces.single()
        val before = model.metricsForTests()
        val scans = model.indexedScanCountForTests()
        val changed = scriptRoot.resolve("script-500.eternal.kts")

        Files.writeString(changed, "val value500: Int = 501")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(changed)?.refresh(false, false)
        model.analyzePathSynchronouslyForTests(changed)

        val after = model.metricsForTests()
        assertEquals(scans, model.indexedScanCountForTests())
        assertEquals(before.workspaceScans, after.workspaceScans)
        assertEquals(before.configurationReloads, after.configurationReloads)
        assertEquals(before.abiAnalyses + 1, after.abiAnalyses)
        val afterWorkspace = model.current().workspaces.single()
        assertSame(beforeWorkspace.generatedFiles, afterWorkspace.generatedFiles)
        assertSame(beforeWorkspace.conflicts, afterWorkspace.conflicts)
        assertTrue(afterWorkspace.pendingInvalidatedNames.isEmpty())
        assertEquals(beforeWorkspace.configurationFingerprint, afterWorkspace.configurationFingerprint)
        assertEquals(beforeWorkspace.digest, afterWorkspace.digest)
        assertNotEquals(
            beforeWorkspace.fileAbis.getValue(beforeWorkspace.sourceUrls.single { it.endsWith("script-500.eternal.kts") })
                .contentDigest,
            afterWorkspace.fileAbis.getValue(afterWorkspace.sourceUrls.single { it.endsWith("script-500.eternal.kts") })
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

    fun testDisabledDeclarationsStayOutOfTheSyntheticModelUntilEnabled() {
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
        assertFalse(disabled.url in before.activeSourceUrls)
        assertContains(before.generatedText, "activeValue")
        assertFalse(before.generatedText.contains("disabledValue"))

        Files.writeString(disabled.toNioPath(), "val changedDisabledValue: String = \"changed\"")
        disabled.refresh(false, false)
        model.analyzePathSynchronouslyForTests(disabled.toNioPath())

        val afterDisabledEdit = model.current().workspaces.single()
        assertSame(before.generatedFiles, afterDisabledEdit.generatedFiles)
        assertSame(before.conflicts, afterDisabledEdit.conflicts)
        assertTrue(afterDisabledEdit.pendingInvalidatedNames.isEmpty())
        assertFalse(afterDisabledEdit.generatedText.contains("changedDisabledValue"))

        WriteCommandAction.runWriteCommandAction(project) {
            disabled.rename(this, "provider.eternal.kts")
        }
        model.rebuildSynchronouslyForTests(base)

        val enabled = model.current().workspaces.single()
        assertTrue(disabled.url in enabled.activeSourceUrls)
        assertContains(enabled.generatedText, "changedDisabledValue")

        WriteCommandAction.runWriteCommandAction(project) {
            disabled.rename(this, "-provider.eternal.kts")
        }
        model.rebuildSynchronouslyForTests(base)

        val disabledAgain = model.current().workspaces.single()
        assertFalse(disabled.url in disabledAgain.activeSourceUrls)
        assertFalse(disabledAgain.generatedText.contains("changedDisabledValue"))
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
        Files.write(manifest, environment(workspace, IdeProtocol.VERSION))
    }

    private fun environment(
        workspace: Path,
        protocolVersion: Int,
        classpath: List<java.net.URI> = emptyList(),
        kotlinVersion: String = "2.4.10"
    ): ByteArray = IdeEnvironmentCodec.encode(
        IdeEnvironment(
            protocolVersion,
            UUID.nameUUIDFromBytes(workspace.toString().toByteArray()).toString(),
            "2.1.0-test",
            kotlinVersion,
            "fixture-${workspace.fileName}-$protocolVersion",
            "scripts",
            classpath,
            emptyList()
        )
    )
}
