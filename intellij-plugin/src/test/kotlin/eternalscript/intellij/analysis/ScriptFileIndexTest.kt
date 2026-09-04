package eternalscript.intellij.analysis

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import eternalscript.ide.protocol.IdeEnvironment
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.intellij.workspace.EternalScriptWorkspaceDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

internal class ScriptFileIndexTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    fun testConcurrentUpdatesForOneWorkspaceDoNotOverwriteEachOther() {
        val workspaceRoot = Path.of(myFixture.tempDirPath).toAbsolutePath().normalize()
        val scriptRoot = Files.createDirectories(workspaceRoot.resolve("scripts"))
        val paths = (0 until FILE_COUNT).map { index ->
            scriptRoot.resolve("script-$index.eternal.kts").also { path ->
                Files.writeString(path, "val value$index = $index")
            }
        }
        VirtualFileManager.getInstance().syncRefresh()
        val descriptor = EternalScriptWorkspaceDescriptor(
            id = "workspace",
            manifest = workspaceRoot.resolve(IdeProtocol.ENVIRONMENT_FILE),
            manifestDigest = "manifest",
            workspaceRoot = workspaceRoot,
            scriptRoot = scriptRoot,
            environment = environment()
        )
        val fileIndex = ScriptFileIndex()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(WORKER_COUNT)
        try {
            val updates = paths.map { path ->
                executor.submit<IndexedScriptFile?> {
                    check(start.await(30, TimeUnit.SECONDS))
                    fileIndex.update(descriptor, path)
                }
            }
            start.countDown()
            updates.forEach { update -> checkNotNull(update.get(30, TimeUnit.SECONDS)) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(FILE_COUNT, fileIndex.files(descriptor.id).size)
        assertEquals(paths.toSet(), fileIndex.files(descriptor.id).values.mapTo(linkedSetOf(), IndexedScriptFile::path))
    }

    private fun environment(): IdeEnvironment = IdeEnvironment(
        UUID.nameUUIDFromBytes("script-file-index-test".toByteArray()).toString(),
        "test-environment",
        "scripts",
        emptyList()
    )

    private companion object {
        const val FILE_COUNT: Int = 64
        const val WORKER_COUNT: Int = 16
    }
}
