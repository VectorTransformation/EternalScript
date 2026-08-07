package eternalScript.core.manager

import eternalScript.core.environment.EnvironmentRefreshRequest
import eternalScript.core.script.classpath.ScriptPluginClasspathCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentRefreshControllerTest {
    @Test
    fun `requests merge until the server is ready`() {
        val lifecycle = ProjectLifecycleFence().apply { open() }
        var ready = false
        val dispatched = mutableListOf<EnvironmentRefreshRequest>()
        val controller = EnvironmentRefreshController(
            lifecycle = lifecycle,
            canDrain = { ready },
            operationActive = { false },
            dispatch = { request ->
                dispatched += request
                true
            }
        )
        controller.open()

        controller.request(
            request(
                revision = 1,
                loadScripts = true,
                disabledPlugins = setOf("Alpha")
            )
        )
        controller.request(
            request(
                revision = 2,
                disabledPlugins = setOf("Beta"),
                metadataApplied = true
            )
        )
        assertTrue(dispatched.isEmpty())

        ready = true
        controller.drain()

        val merged = dispatched.single()
        assertEquals(2, merged.capture.revision)
        assertTrue(merged.loadScripts)
        assertEquals(setOf("Alpha", "Beta"), merged.disabledPlugins)
        assertTrue(merged.metadataApplied)
        assertNull(controller.pendingRequest())
    }

    @Test
    fun `failed dispatch is requeued and retried outside the queue lock`() {
        val lifecycle = ProjectLifecycleFence().apply { open() }
        var attempts = 0
        lateinit var controller: EnvironmentRefreshController
        controller = EnvironmentRefreshController(
            lifecycle = lifecycle,
            canDrain = { true },
            operationActive = { false },
            dispatch = {
                attempts += 1
                if (attempts == 1) {
                    assertFalse(controller.pendingRequest() != null)
                    false
                } else {
                    true
                }
            }
        )
        controller.open()

        controller.request(request(revision = 3))

        assertEquals(2, attempts)
        assertNull(controller.pendingRequest())
    }

    private fun request(
        revision: Long,
        loadScripts: Boolean = false,
        disabledPlugins: Set<String> = emptySet(),
        metadataApplied: Boolean = false
    ) = EnvironmentRefreshRequest(
        capture = ScriptPluginClasspathCapture(
            revision = revision,
            parentClassLoader = javaClass.classLoader,
            coreFiles = emptyList(),
            plugins = emptyList()
        ),
        loadScripts = loadScripts,
        disabledPlugins = disabledPlugins,
        metadataApplied = metadataApplied
    )
}
