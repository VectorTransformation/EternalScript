package eternalScript

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class EternalScriptLoaderDependencyTest {
    @Test
    fun `paper runtime declares the coroutines JVM artifact directly`() {
        val descriptor = checkNotNull(
            javaClass.getResourceAsStream("/paper-plugin.yml")
        ) {
            "Generated paper-plugin.yml is missing from the test runtime."
        }.bufferedReader().use { reader ->
            reader.readText()
        }

        assertContains(
            descriptor,
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0"
        )
        assertFalse(
            descriptor.lineSequence().any { line ->
                line.trim() == "- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0"
            },
            "The Gradle multiplatform coordinate must not be passed to Paper's Maven resolver."
        )
    }
}
