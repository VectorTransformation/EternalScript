package eternalscript.intellij.model

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class EternalScriptProjectModelStoreTest {
    @Test
    fun staleSnapshotCannotOverwriteTheAcceptedState() {
        val store = EternalScriptProjectModelStore()
        val initial = store.snapshot()
        val first = snapshot(version = 1, digest = "first")
        val stale = snapshot(version = 2, digest = "stale")

        assertTrue(store.compareAndSet(initial, first))
        assertFalse(store.compareAndSet(initial, stale))

        assertSame(first, store.snapshot())
    }

    @Test
    fun projectSnapshotsAdvanceAtomically() {
        val store = EternalScriptProjectModelStore()
        val first = snapshot(version = 1, digest = "first")
        val second = snapshot(version = 2, digest = "second")

        assertTrue(store.compareAndSet(store.snapshot(), first))
        assertTrue(store.compareAndSet(first, second))

        assertSame(second, store.snapshot())
    }

    private fun snapshot(version: Long, digest: String): EternalScriptProjectSnapshot =
        EternalScriptProjectSnapshot(emptyList(), digest, version)
}
