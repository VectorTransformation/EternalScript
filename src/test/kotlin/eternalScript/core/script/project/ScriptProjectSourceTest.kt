package eternalScript.core.script.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptProjectSourceTest {
    @Test
    fun `ordinary package imports and visibility declarations are preserved`() {
        val providerText = """
            @file:Suppress("unused")

            package project.provider

            import kotlin.math.abs

            internal fun shared(): Int = hidden()
            private fun hidden(): Int = abs(-7)
        """.trimIndent()
        val consumerText = """
            package project.consumer

            import project.provider.shared

            internal fun consume(): Int = shared()
        """.trimIndent()
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile("consumer.kt", consumerText),
                ScriptProjectFile("provider.kt", providerText)
            )
        )

        assertEquals(listOf("consumer.kt", "provider.kt"), project.files.map { it.name })
        assertEquals(consumerText, project.files[0].text)
        assertEquals(providerText, project.files[1].text)

        val generated = project.module.files.filter { file -> file.facadeClassName != null }
        val provider = generated.single { file ->
            file.text.contains("package project.provider")
        }
        val consumer = generated.single { file ->
            file.text.contains("package project.consumer")
        }

        assertTrue(provider.text.contains("""@file:Suppress("unused")"""))
        assertTrue(provider.text.contains("import kotlin.math.abs"))
        assertTrue(provider.text.contains("internal fun shared()"))
        assertTrue(provider.text.contains("private fun hidden()"))
        assertTrue(consumer.text.contains("import project.provider.shared"))
    }

    @Test
    fun `composition is deterministic without merging Kotlin files`() {
        val a = ScriptProjectFile(
            "a.kt",
            """
            package project.a

            import kotlin.math.abs

            internal fun fromA() = abs(-1)
            """.trimIndent()
        )
        val b = ScriptProjectFile(
            "nested\\b.kt",
            """
            package project.b

            import project.a.fromA
            import kotlin.math.max

            internal fun fromB() = max(fromA(), 2)
            """.trimIndent()
        )

        val first = ScriptProjectSource.compose(listOf(b, a))
        val second = ScriptProjectSource.compose(listOf(a, b))

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(listOf("a.kt", "nested/b.kt"), first.files.map { it.name })
        assertEquals(listOf(a.text, b.text), first.files.map { it.text })
    }

    @Test
    fun `generated module lines map back to ordinary Kotlin files`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "a.kt",
                    """
                    package project.mapping

                    import kotlin.math.abs

                    internal fun mapped() = abs(-1)
                    """.trimIndent()
                )
            )
        )
        val generated = project.module.files.single { file -> file.facadeClassName != null }
        val generatedLines = generated.text.lines()
        val importLine = generatedLines.indexOf("import kotlin.math.abs") + 1
        val declarationLine = generatedLines.indexOf("internal fun mapped() = abs(-1)") + 1

        assertEquals(
            ScriptProjectPosition("a.kt", 3, 8),
            project.position(generated.name, importLine, 8)
        )
        assertEquals(
            ScriptProjectPosition("a.kt", 5, 5),
            project.position(generated.name, declarationLine, 5)
        )
    }

    @Test
    fun `ordinary file annotations and package declarations are accepted`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "annotations.kt",
                    """
                    @file:Suppress("unused")

                    package project.annotations

                    private const val hidden = 1
                    internal fun visible() = hidden
                    """.trimIndent()
                )
            )
        )

        val generated = project.module.files.single { file -> file.facadeClassName != null }
        assertTrue(generated.text.contains("""@file:Suppress("unused")"""))
        assertTrue(generated.text.contains("package project.annotations"))
        assertTrue(generated.text.contains("private const val hidden"))
        assertTrue(generated.text.contains("internal fun visible()"))
    }

    @Test
    fun `case insensitive duplicate Kotlin source paths are rejected`() {
        assertFailsWith<ScriptProjectCompositionException> {
            ScriptProjectSource.compose(
                listOf(
                    ScriptProjectFile("A.kt", "fun a() = Unit"),
                    ScriptProjectFile("a.kt", "fun b() = Unit")
                )
            )
        }
    }

    @Test
    fun `absolute and escaping Kotlin source paths are rejected`() {
        listOf(
            "C:\\scripts\\a.kt",
            "\\\\server\\scripts\\a.kt",
            "/scripts/a.kt",
            "../a.kt",
            "scripts/./a.kt",
            "scripts//a.kt"
        ).forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                ScriptProjectSource.compose(
                    listOf(ScriptProjectFile(path, "fun valid() = Unit"))
                )
            }
        }
    }

    @Test
    fun `non Kotlin source extensions are rejected`() {
        listOf("legacy.kts", "legacy.eternal.kts", "UPPER.KT", "notes.txt").forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                ScriptProjectSource.compose(
                    listOf(ScriptProjectFile(path, "fun valid() = Unit"))
                )
            }
        }
    }

    @Test
    fun `source text line endings and byte order marks are normalized`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "normalized.kt",
                    "\uFEFFpackage project.normalized\r\n\r\ninternal val value = 1\r"
                )
            )
        )

        assertEquals(
            " package project.normalized\n\ninternal val value = 1\n",
            project.files.single().text
        )
    }
}
