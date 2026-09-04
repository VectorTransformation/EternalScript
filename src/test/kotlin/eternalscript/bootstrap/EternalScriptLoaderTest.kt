package eternalscript.bootstrap

import eternalscript.EternalScriptLoader
import java.nio.charset.StandardCharsets
import java.util.Properties
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EternalScriptLoaderTest {
    @Test
    fun `generated runtime library manifest is complete and version aligned`() {
        val properties = Properties()
        assertNotNull(
            EternalScriptLoader::class.java.getResourceAsStream(
                "/eternalscript-runtime-libraries.properties"
            )
        ).bufferedReader(StandardCharsets.UTF_8).use(properties::load)

        val libraries = assertNotNull(properties.getProperty("libraries"))
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)

        assertEquals(libraries.distinct(), libraries)
        assertTrue(libraries.all { coordinate -> coordinate.count { it == ':' } == 2 })
        assertTrue(
            "org.jetbrains.kotlin:kotlin-stdlib:${KotlinCompilerVersion.VERSION}" in libraries
        )
        assertTrue(
            "org.jetbrains.kotlin:kotlin-compiler-embeddable:${KotlinCompilerVersion.VERSION}" in libraries
        )
        assertTrue(
            "org.jetbrains.kotlin:kotlin-metadata-jvm:${KotlinCompilerVersion.VERSION}" in libraries
        )
        assertTrue("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0" in libraries)
        assertEquals(1, libraries.count { coordinate -> coordinate == "org.xerial:sqlite-jdbc:3.53.4.0" })
        assertFalse(
            libraries.any { coordinate ->
                coordinate.startsWith("org.jetbrains.kotlinx:kotlinx-coroutines-core:")
            }
        )
        assertFalse(libraries.any { coordinate -> "kotlin-stdlib-jdk8" in coordinate })
    }
}
