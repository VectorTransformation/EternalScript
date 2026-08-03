package eternalScript.core.script.project

import eternalScript.api.script.EternalScript
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptProjectEternalScriptDiscoveryTest {
    @Test
    fun `discovers every concrete EternalScript subclass in deterministic order`() {
        val discovered = discoverEternalScriptTypes(
            listOf(
                DiscoveryBeta::class.java.name,
                DiscoveryAbstract::class.java.name,
                DiscoveryAlpha::class.java.name,
                EternalScript::class.java.name,
                DiscoverySyntheticHolder::class.java.name
            ),
            javaClass.classLoader
        )

        assertEquals(
            listOf(DiscoveryAlpha::class.java, DiscoveryBeta::class.java),
            discovered
        )
    }

    @Test
    fun `ordinary module accepts multiple class based entries without annotations`() {
        val project = ScriptProjectSource.compose(
            listOf(
                ScriptProjectFile(
                    "entries.kt",
                    """
                    package discovery

                    import eternalScript.api.script.EternalScript

                    class FirstEntry : EternalScript()
                    class SecondEntry : EternalScript()
                    """.trimIndent()
                )
            )
        )

        val module = project.module

        assertEquals(listOf("entries.kt"), module.files.map { file -> file.name })
    }
}

private class DiscoveryAlpha : EternalScript()

private class DiscoveryBeta : EternalScript()

private abstract class DiscoveryAbstract : EternalScript()

private class DiscoverySyntheticHolder
