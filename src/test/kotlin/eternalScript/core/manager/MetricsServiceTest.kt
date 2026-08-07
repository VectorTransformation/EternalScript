package eternalScript.core.manager

import org.bukkit.plugin.Plugin
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsServiceTest {
    @Test
    fun `start and stop are idempotent and shutdown is called once`() {
        var starts = 0
        var stops = 0
        val service = MetricsService(
            plugin = proxyPlugin(),
            enabled = { true },
            metricsFactory = { _, _ ->
                starts += 1
                MetricsHandle { stops += 1 }
            }
        )

        service.start()
        service.start()
        service.stop()
        service.stop()

        assertEquals(1, starts)
        assertEquals(1, stops)
    }

    @Suppress("UNCHECKED_CAST")
    private fun proxyPlugin(): Plugin = Proxy.newProxyInstance(
        Plugin::class.java.classLoader,
        arrayOf(Plugin::class.java)
    ) { instance, method, arguments ->
        when (method.name) {
            "toString" -> "MetricsTestPlugin"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === arguments?.firstOrNull()
            else -> null
        }
    } as Plugin
}
