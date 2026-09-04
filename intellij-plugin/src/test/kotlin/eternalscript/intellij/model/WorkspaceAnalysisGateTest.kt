package eternalscript.intellij.model

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class WorkspaceAnalysisGateTest {
    @Test
    fun sameWorkspaceCannotEnterUntilThePreviousAnalysisLeaves() {
        val gate = WorkspaceAnalysisGate()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val first = Thread {
            gate.withWorkspace("workspace") {
                firstEntered.countDown()
                check(releaseFirst.await(30, TimeUnit.SECONDS))
            }
        }
        val second = Thread {
            gate.withWorkspace("workspace") {
                secondEntered.countDown()
            }
        }

        first.start()
        assertTrue(firstEntered.await(30, TimeUnit.SECONDS))
        second.start()
        try {
            waitForBlocked(second)
            assertEquals(1L, secondEntered.count)
        } finally {
            releaseFirst.countDown()
            first.join(30_000)
            second.join(30_000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(0L, secondEntered.count)
    }

    @Test
    fun multiWorkspaceTransactionDoesNotExposeAHalfInstalledWorkspace() {
        val gate = WorkspaceAnalysisGate()
        val modelPublished = CountDownLatch(1)
        val releaseIndexInstall = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val workspaceA = gate.javaClass.name
        val workspaceB = workspaceA.reversed()
        val modelVersion = AtomicInteger()
        val indexVersion = AtomicInteger()
        val observed = AtomicReference<Pair<Int, Int>>()
        val discovery = Thread {
            gate.withWorkspaces(listOf(workspaceB, workspaceA)) {
                modelVersion.set(1)
                modelPublished.countDown()
                check(releaseIndexInstall.await(30, TimeUnit.SECONDS))
                indexVersion.set(1)
            }
        }
        val incremental = Thread {
            gate.withWorkspace(workspaceA) {
                observed.set(modelVersion.get() to indexVersion.get())
                workerEntered.countDown()
            }
        }

        discovery.start()
        assertTrue(modelPublished.await(30, TimeUnit.SECONDS))
        incremental.start()
        try {
            waitForBlocked(incremental)
            assertEquals(1L, workerEntered.count)
            assertEquals(0, indexVersion.get())
        } finally {
            releaseIndexInstall.countDown()
            discovery.join(30_000)
            incremental.join(30_000)
        }

        assertFalse(discovery.isAlive)
        assertFalse(incremental.isAlive)
        assertEquals(1 to 1, observed.get())
    }

    private fun waitForBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (thread.state != Thread.State.BLOCKED && thread.isAlive && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }
}
