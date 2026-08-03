package eternalScript.core.environment

import eternalScript.core.script.classpath.ScriptPluginClasspathCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentRefreshRequestTest {
    @Test
    fun `newer capture invalidates metadata applied by an older retry`() {
        val older = EnvironmentRefreshRequest(
            capture = capture(10),
            disabledPlugins = setOf("OldPlugin"),
            metadataApplied = true
        )
        val newer = EnvironmentRefreshRequest(
            capture = capture(11),
            loadScripts = true,
            disabledPlugins = setOf("NewPlugin")
        )

        val merged = older.merge(newer)

        assertEquals(11, merged.capture.revision)
        assertFalse(merged.metadataApplied)
        assertTrue(merged.loadScripts)
        assertEquals(setOf("OldPlugin", "NewPlugin"), merged.disabledPlugins)
    }

    @Test
    fun `same capture keeps completed metadata while cleanup retry merges`() {
        val capture = capture(20)
        val pending = EnvironmentRefreshRequest(
            capture = capture,
            disabledPlugins = setOf("Dependency")
        )
        val retry = pending.copy(metadataApplied = true)

        val merged = pending.merge(retry)

        assertEquals(20, merged.capture.revision)
        assertTrue(merged.metadataApplied)
        assertEquals(setOf("Dependency"), merged.disabledPlugins)
    }

    @Test
    fun `older retry cannot replace a newer capture`() {
        val newer = EnvironmentRefreshRequest(
            capture = capture(31),
            metadataApplied = true
        )
        val olderRetry = EnvironmentRefreshRequest(
            capture = capture(30),
            loadScripts = true
        )

        val merged = newer.merge(olderRetry)

        assertEquals(31, merged.capture.revision)
        assertTrue(merged.metadataApplied)
        assertTrue(merged.loadScripts)
    }

    private fun capture(revision: Long) = ScriptPluginClasspathCapture(
        revision = revision,
        parentClassLoader = javaClass.classLoader,
        coreFiles = emptyList(),
        plugins = emptyList()
    )
}
